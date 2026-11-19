package com.zondamarket.integrations.google;

import com.zondamarket.identity.service.GoogleOidcClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
class SpringGoogleOidcClient implements GoogleOidcClient {
    private final URI redirectUri;
    private final ClientRegistration registration;
    private final JwtDecoder idTokens;
    private final RestClientAuthorizationCodeTokenResponseClient tokens;

    SpringGoogleOidcClient(
            @Value("${zonda.google.client-id}") String clientId,
            @Value("${zonda.google.client-secret}") String clientSecret,
            @Value("${zonda.google.redirect-uri}") URI redirectUri,
            @Value("${zonda.google.issuer}") String issuer,
            @Value("${zonda.google.authorization-uri}") String authorizationUri,
            @Value("${zonda.google.token-uri}") String tokenUri,
            @Value("${zonda.google.jwk-set-uri}") String jwksUri) {
        this.redirectUri = redirectUri;
        if (clientId.isBlank() && clientSecret.isBlank()) {
            registration = null;
            idTokens = null;
            tokens = null;
            return;
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalArgumentException("Configurá GOOGLE_CLIENT_ID y GOOGLE_CLIENT_SECRET juntos.");
        }
        validateRedirect(redirectUri);
        registration = ClientRegistration.withRegistrationId("google")
                .clientId(clientId).clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri.toString()).scope("openid", "email")
                .issuerUri(issuer).authorizationUri(authorizationUri).tokenUri(tokenUri).jwkSetUri(jwksUri)
                .build();

        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        tokens = new RestClientAuthorizationCodeTokenResponseClient();
        tokens.setRestClient(RestClient.builder().requestFactory(factory)
                .configureMessageConverters(converters -> converters.registerDefaults()
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());

        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(new OidcIdTokenValidator(registration));
        idTokens = decoder;
    }

    @Override
    public boolean enabled() { return registration != null; }

    @Override
    public URI redirectUri() { return redirectUri; }

    @Override
    public URI authorizationUri(String state, String nonce, String verifier) {
        return URI.create(authorization(state, nonce, verifier).getAuthorizationRequestUri());
    }

    @Override
    public Optional<Identity> authenticate(String code, String state, String nonce, String verifier) {
        var response = OAuth2AuthorizationResponse.success(code).state(state)
                .redirectUri(redirectUri.toString()).build();
        var exchange = new OAuth2AuthorizationExchange(authorization(state, nonce, verifier), response);
        try {
            var tokenResponse = tokens.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration, exchange));
            if (!(tokenResponse.getAdditionalParameters().get("id_token") instanceof String rawId)) {
                return Optional.empty();
            }
            var id = idTokens.decode(rawId);
            String subject = id.getSubject();
            String email = id.getClaimAsString("email");
            if (!List.of(registration.getClientId()).equals(id.getAudience())
                    || !nonce.equals(id.getClaimAsString("nonce"))
                    || !Boolean.TRUE.equals(id.getClaims().get("email_verified"))
                    || subject.isBlank() || subject.length() > 255 || email == null) {
                return Optional.empty();
            }
            String normalized = email.strip().toLowerCase(Locale.ROOT);
            if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+")) {
                return Optional.empty();
            }
            return Optional.of(new Identity(registration.getProviderDetails().getIssuerUri(), subject, normalized));
        } catch (OAuth2AuthorizationException | JwtException | RestClientException exception) {
            return Optional.empty();
        }
    }

    private OAuth2AuthorizationRequest authorization(String state, String nonce, String verifier) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId()).redirectUri(redirectUri.toString())
                .scopes(registration.getScopes()).state(state)
                .attributes(Map.of(PkceParameterNames.CODE_VERIFIER, verifier))
                .additionalParameters(Map.of("nonce", nonce, "prompt", "select_account",
                        PkceParameterNames.CODE_CHALLENGE, challenge(verifier),
                        PkceParameterNames.CODE_CHALLENGE_METHOD, "S256"))
                .build();
    }

    private static String challenge(String verifier) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void validateRedirect(URI uri) {
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !"/login/oauth2/code/google".equals(uri.getPath())
                || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("GOOGLE_REDIRECT_URI debe apuntar al callback de Zonda por HTTPS o loopback.");
        }
    }
}
