package com.zondamarket.identity.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.zondamarket.identity.domain.RefreshSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
class SessionTokens {
    static final Duration ACCESS_TTL = Duration.ofMinutes(10);
    static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final String ISSUER = "zonda-market";
    private static final String ACCESS = "zonda-api";
    private static final String REFRESH = "zonda-refresh";

    record Claims(UUID accountId, UUID familyId, long version, long generation, String jti) {}

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;

    SessionTokens(@Value("${zonda.auth.jwt-key}") String secret, Clock clock) {
        this.clock = clock;
        byte[] key = Base64.getDecoder().decode(secret);
        if (key.length < 32) {
            throw new IllegalArgumentException("AUTH_JWT_KEY debe codificar al menos 32 bytes en Base64.");
        }
        var signingKey = new SecretKeySpec(key, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
        decoder = NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER)));
    }

    String issueAccess(RefreshSession session, Instant now) {
        Instant expiry = now.plus(ACCESS_TTL);
        if (expiry.isAfter(session.getExpiresAt())) {
            expiry = session.getExpiresAt();
        }
        return encode(claims(session, ACCESS, now, expiry).id(UUID.randomUUID().toString()));
    }

    String issueRefresh(RefreshSession session, Instant now, String jti) {
        return encode(claims(session, REFRESH, now, session.getExpiresAt())
                .id(jti).claim("generation", session.getGeneration()));
    }

    Jwt decodeAccess(String value) {
        Jwt jwt = decoder.decode(value);
        decode(jwt, ACCESS);
        return jwt;
    }

    Claims access(Jwt jwt) {
        return decode(jwt, ACCESS);
    }

    Claims refresh(String value) {
        return decode(decoder.decode(value), REFRESH);
    }

    private JwtClaimsSet.Builder claims(RefreshSession session, String audience, Instant now, Instant expiry) {
        return JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(audience))
                .subject(session.getAccount().getId().toString()).issuedAt(now).expiresAt(expiry)
                .claim("sid", session.getId().toString()).claim("version", session.getSessionVersion());
    }

    private String encode(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private Claims decode(Jwt jwt, String audience) {
        if (!List.of(audience).equals(jwt.getAudience()) || jwt.getExpiresAt() == null
                || !clock.instant().isBefore(jwt.getExpiresAt())
                || jwt.getSubject() == null || !(jwt.getClaims().get("sid") instanceof String family)
                || !(jwt.getClaims().get("version") instanceof Number version)) {
            throw new BadJwtException("Claims inválidos.");
        }
        long generation = 0;
        if (REFRESH.equals(audience)) {
            if (jwt.getId() == null || !(jwt.getClaims().get("generation") instanceof Number rotation)) {
                throw new BadJwtException("Refresh inválido.");
            }
            generation = rotation.longValue();
        }
        try {
            return new Claims(UUID.fromString(jwt.getSubject()), UUID.fromString(family),
                    version.longValue(), generation, jwt.getId());
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException("Identificadores inválidos.", exception);
        }
    }

    static String hash(String jti) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(jti.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
