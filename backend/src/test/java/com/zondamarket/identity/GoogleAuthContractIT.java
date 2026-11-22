package com.zondamarket.identity;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.zondamarket.identity.OidcTestProvider.query;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class GoogleAuthContractIT {
    private static final String HOST = "localhost:3000";
    private static final String ORIGIN = "http://" + HOST;
    private static final String CALLBACK = "/login/oauth2/code/google";
    private static final String STATUS = "/api/v1/me/identities/google";
    private static final String EXCHANGE = "/api/v1/auth/oauth/exchange";
    private static final String BROWSER = "__Host-zonda-oauth";
    private static final String REFRESH = "__Host-zonda-refresh";
    private static final String PASSWORD = "clave-ficticia-de-prueba";
    private static final OidcTestProvider OIDC = new OidcTestProvider();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry properties) {
        properties.add("zonda.google.client-id", () -> OidcTestProvider.CLIENT_ID);
        properties.add("zonda.google.client-secret", () -> OidcTestProvider.CLIENT_SECRET);
        properties.add("zonda.google.redirect-uri", () -> ORIGIN + CALLBACK);
        properties.add("zonda.google.issuer", OIDC::issuer);
        properties.add("zonda.google.authorization-uri", () -> OIDC.issuer() + "/authorize");
        properties.add("zonda.google.token-uri", () -> OIDC.issuer() + "/token");
        properties.add("zonda.google.jwk-set-uri", () -> OIDC.issuer() + "/keys");
    }

    @AfterAll
    static void stopOidc() {
        OIDC.close();
    }

    @Test
    void createsAndRestoresAGoogleAccountByIssuerAndSubject() throws Exception {
        mvc.perform(get("/api/v1/auth/oauth/google").header(HttpHeaders.HOST, HOST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/v1/auth/oauth/google").header(HttpHeaders.HOST, "127.0.0.1:3000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        String email = UUID.randomUUID() + "@gmail.com";
        String subject = UUID.randomUUID().toString();
        var first = googleGrant(begin(), subject, email);
        var session = redeem(first).andExpect(status().isOk())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        contains(containsString("SameSite=Strict"), containsString("Max-Age=0"))))
                .andExpect(jsonPath("$.roles[0]").value("USER")).andReturn();
        var profile = mvc.perform(bearer(get("/api/v1/me"), access(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true)).andReturn();
        mvc.perform(command("/api/v1/auth/login").content(credentials(email)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        var repeated = redeem(googleGrant(begin(), subject, email())).andExpect(status().isOk()).andReturn();
        mvc.perform(bearer(get("/api/v1/me"), access(repeated)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(field(profile, "$.id")))
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(bearer(get(STATUS), access(repeated))).andExpect(jsonPath("$.linked").value(true));

        var external = redeem(googleGrant(begin(), UUID.randomUUID().toString(), email()))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(bearer(get("/api/v1/me"), access(external)))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void requiresExplicitLinkAndRejectsAccessFromAnotherAccount() throws Exception {
        String email = email();
        String subject = UUID.randomUUID().toString();
        var local = register(email);
        var another = register(email());
        redeem(googleGrant(begin(), subject, email)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOOGLE_LINK_REQUIRED"));
        mvc.perform(get(STATUS)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get(STATUS), access(local))).andExpect(jsonPath("$.linked").value(false));

        mvc.perform(bearer(command(STATUS + "/start"), access(another))
                .cookie(cookie(local, REFRESH)).content(password(PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearer(command(STATUS + "/start"), access(local))
                .cookie(cookie(local, REFRESH)).content(password("incorrecta")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(bearer(post(STATUS + "/start").header(HttpHeaders.HOST, HOST).header(HttpHeaders.ORIGIN, "https://otro.example")
                        .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON), access(local))
                .cookie(cookie(local, REFRESH)).content(password(PASSWORD)))
                .andExpect(status().isForbidden());

        var wrongEmail = googleGrant(beginLink(local), subject, email());
        redeem(wrongEmail, cookie(local, REFRESH)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOOGLE_EMAIL_MISMATCH"));

        var grant = googleGrant(beginLink(local), subject, email);
        var linked = redeem(grant, cookie(local, REFRESH)).andExpect(status().isOk()).andReturn();
        mvc.perform(bearer(get(STATUS), access(linked))).andExpect(jsonPath("$.linked").value(true));
        mvc.perform(bearer(get(STATUS), access(another))).andExpect(jsonPath("$.linked").value(false));
        mvc.perform(bearer(get("/api/v1/me"), access(linked)))
                .andExpect(jsonPath("$.emailVerified").value(false));
        var login = redeem(googleGrant(begin(), subject, email)).andExpect(status().isOk()).andReturn();
        mvc.perform(bearer(get("/api/v1/me"), access(login))).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void refusesToLinkAfterTheReauthenticatedSessionIsRevoked() throws Exception {
        String email = email();
        var local = register(email);
        var grant = googleGrant(beginLink(local), UUID.randomUUID().toString(), email);
        mvc.perform(command("/api/v1/auth/logout").cookie(cookie(local, REFRESH))).andExpect(status().isNoContent());
        redeem(grant, cookie(local, REFRESH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION"));
    }

    @Test
    void rejectsWrongStateBrowserNonceIssuerAudienceAndUnverifiedEmail() throws Exception {
        var start = begin();
        String code = OIDC.approve(start.authorization(), "subject", email());
        callback(code, "a".repeat(43), start.browser()).andExpect(header().string("Location", "/oauth/retorno#error=invalid"));
        callback(code, start.state(), new Cookie(BROWSER, "a".repeat(43)))
                .andExpect(header().string("Location", "/oauth/retorno#error=invalid"));
        var accepted = callback(code, start.state(), start.browser()).andReturn();
        assertTrue(location(accepted).getFragment().startsWith("code="));
        callback(code, start.state(), start.browser())
                .andExpect(header().string("Location", "/oauth/retorno#error=invalid"));

        for (String bad : List.of("nonce", "issuer", "audience", "email")) {
            var next = begin();
            String invalid = OIDC.approve(next.authorization(), "subject", email(),
                    "audience".equals(bad) ? "otro-client" : OidcTestProvider.CLIENT_ID,
                    "issuer".equals(bad) ? "https://otro.example" : OIDC.issuer(),
                    "nonce".equals(bad) ? "otro-nonce" : query(next.authorization()).get("nonce"),
                    !"email".equals(bad));
            callback(invalid, next.state(), next.browser())
                    .andExpect(header().string("Location", "/oauth/retorno#error=invalid"));
        }
        var declined = begin();
        mvc.perform(get(CALLBACK).header(HttpHeaders.HOST, HOST).param("state", declined.state())
                        .param("error", "access_denied").cookie(declined.browser()))
                .andExpect(header().string("Location", "/oauth/retorno#error=denied"));
    }

    @Test
    void enforcesExpiryBrowserBindingAndSingleUseUnderConcurrency() throws Exception {
        var stale = begin();
        db.sql("UPDATE zonda_market.oauth_attempt SET expires_at = now() - interval '1 second'").update();
        String expiredAuthorization = OIDC.approve(stale.authorization(), "expired", email());
        callback(expiredAuthorization, stale.state(), stale.browser())
                .andExpect(header().string("Location", "/oauth/retorno#error=invalid"));

        var expired = googleGrant(begin(), UUID.randomUUID().toString(), email());
        db.sql("UPDATE zonda_market.oauth_exchange SET expires_at = now() - interval '1 second'").update();
        redeem(expired).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GOOGLE_EXCHANGE_INVALID"));

        var active = googleGrant(begin(), UUID.randomUUID().toString(), email());
        redeem(new Grant(active.code(), new Cookie(BROWSER, "a".repeat(43))))
                .andExpect(status().isUnauthorized());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<Integer>) () -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("El intercambio no se inició.");
                }
                return redeem(active).andReturn().getResponse().getStatus();
            };
            var first = workers.submit(action);
            var second = workers.submit(action);
            start.countDown();
            assertEquals(List.of(200, 401), List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        }
    }

    private Start begin() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/google").header(HttpHeaders.HOST, HOST))
                .andExpect(status().isFound()).andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure"))).andReturn();
        var authorization = location(result);
        var params = query(authorization);
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals(43, params.get("code_challenge").length());
        assertNotEquals(params.get("state"), params.get("nonce"));
        assertEquals(ORIGIN + CALLBACK, params.get("redirect_uri"));
        return new Start(authorization, cookie(result, BROWSER), false);
    }

    private Start beginLink(MvcResult local) throws Exception {
        var result = mvc.perform(bearer(command(STATUS + "/start"), access(local))
                .cookie(cookie(local, REFRESH)).content(password(PASSWORD))).andExpect(status().isOk()).andReturn();
        return new Start(URI.create(field(result, "$.authorizationUrl")), cookie(result, BROWSER), true);
    }

    private ResultActions callback(String code, String state, Cookie browser) throws Exception {
        return mvc.perform(get(CALLBACK).header(HttpHeaders.HOST, HOST).param("code", code)
                .param("state", state).cookie(browser)).andExpect(status().isFound());
    }

    private Grant googleGrant(Start start, String subject, String email) throws Exception {
        String oidcCode = OIDC.approve(start.authorization(), subject, email);
        var callback = callback(oidcCode, start.state(), start.browser()).andReturn();
        URI location = location(callback);
        assertEquals("/oauth/retorno", location.getPath());
        boolean linking = start.linking();
        assertTrue(location.getFragment().matches("code=[A-Za-z0-9_-]{43}" + (linking ? "&mode=link" : "")));
        String code = location.getFragment().substring("code=".length(), "code=".length() + 43);
        assertNotEquals(oidcCode, code);
        return new Grant(code, cookie(callback, BROWSER));
    }

    private ResultActions redeem(Grant grant, Cookie... cookies) throws Exception {
        var request = command(EXCHANGE).content("{\"code\":\"" + grant.code() + "\"}").cookie(grant.browser());
        for (Cookie cookie : cookies) {
            request.cookie(cookie);
        }
        return mvc.perform(request);
    }

    private MvcResult register(String email) throws Exception {
        return mvc.perform(command("/api/v1/auth/register").content(credentials(email)))
                .andExpect(status().isCreated()).andReturn();
    }

    private static MockHttpServletRequestBuilder command(String path) {
        return post(path).header(HttpHeaders.HOST, HOST).header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String access) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + access);
    }

    private static URI location(MvcResult result) {
        return URI.create(result.getResponse().getHeader(HttpHeaders.LOCATION));
    }

    private static Cookie cookie(MvcResult result, String name) {
        String line = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, line.split(";", 2)[0].substring(name.length() + 1));
    }

    private static String access(MvcResult result) throws Exception {
        return field(result, "$.accessToken");
    }

    private static String field(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private static String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static String password(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }

    private record Start(URI authorization, Cookie browser, boolean linking) {
        String state() { return query(authorization).get("state"); }
    }
    private record Grant(String code, Cookie browser) {}
}
