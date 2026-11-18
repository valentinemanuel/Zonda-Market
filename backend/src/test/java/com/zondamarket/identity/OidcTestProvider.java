package com.zondamarket.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class OidcTestProvider implements AutoCloseable {
    static final String CLIENT_ID = "zonda-test-client";
    static final String CLIENT_SECRET = "fixture-client-secret";
    private final HttpServer server;
    private final RSAKey key;
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();

    private record Approval(String challenge, String redirect, String token) {}

    OidcTestProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("oidc-fixture").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException(exception);
        }
        server.createContext("/keys", exchange -> reply(exchange, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/token", this::token);
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String approve(URI authorization, String subject, String email) throws JOSEException {
        return approve(authorization, subject, email, CLIENT_ID, issuer(), query(authorization).get("nonce"), true);
    }

    String approve(URI authorization, String subject, String email, String audience, String issuer,
            String nonce, boolean verified) throws JOSEException {
        Map<String, String> request = query(authorization);
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce).claim("email", email).claim("email_verified", verified).build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build();
        var jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(key));
        String code = UUID.randomUUID().toString();
        approvals.put(code, new Approval(request.get("code_challenge"), request.get("redirect_uri"), jwt.serialize()));
        return code;
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> request = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var approval = approvals.remove(request.getOrDefault("code", ""));
        if (approval == null || !"authorization_code".equals(request.get("grant_type"))
                || !CLIENT_ID.equals(request.get("client_id")) || !CLIENT_SECRET.equals(request.get("client_secret"))
                || !approval.redirect().equals(request.get("redirect_uri"))
                || !approval.challenge().equals(challenge(request.getOrDefault("code_verifier", "")))) {
            reply(exchange, 400, """
                    {"error":"invalid_grant"}
                    """);
            return;
        }
        reply(exchange, 200, """
                {"access_token":"fictitious","token_type":"Bearer","expires_in":300,"id_token":"%s"}
                """.formatted(approval.token()));
    }

    static Map<String, String> query(URI uri) {
        return parse(uri.getRawQuery());
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> result = new HashMap<>();
        for (String entry : text.split("&")) {
            String[] pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String challenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
