package com.zondamarket.identity;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class AuthContractIT {
    private static final String ORIGIN = "http://127.0.0.1:3000";
    private static final String AUTH = "/api/v1/auth/";
    private static final String ME = "/api/v1/me";
    private static final String COOKIE = "__Host-zonda-refresh";
    private static final String PASSWORD = "clave-de-prueba-2026";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    Clock clock;

    private final AtomicReference<Instant> time = new AtomicReference<>();

    @BeforeEach
    void resetClock() {
        time.set(START);
        when(clock.instant()).thenAnswer(invocation -> time.get());
    }

    @Test
    void registersLogsInAndReturnsOnlyTheAuthenticatedAccount() throws Exception {
        String email = email();
        var registered = register(email);
        mvc.perform(command("register").content(credentials(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        var other = register(email());
        String otherId = JsonPath.read(mvc.perform(me(access(other))).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(me(access(registered)).queryParam("id", otherId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.roles[0]").value("USER"));

        var loggedIn = mvc.perform(command("login").content(credentials(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(me(access(loggedIn))).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get(ME).cookie(refreshCookie(registered))).andExpect(status().isUnauthorized());

        for (String address : List.of(email, email())) {
            mvc.perform(command("login").content(credentials(address, "contraseña-incorrecta")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("El correo o la contraseña son incorrectos."));
        }
        mvc.perform(command("register").content(credentials(email(), "á".repeat(37))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keepsTheBrowserPersistenceChoiceWhenRotatingTheCookie() throws Exception {
        String email = email();
        register(email);
        for (boolean remember : List.of(false, true)) {
            String login = """
                    {"email":"%s","password":"%s","rememberMe":%s}
                    """.formatted(email, PASSWORD, remember);
            var signedIn = mvc.perform(command("login").content(login)).andExpect(status().isOk()).andReturn();
            var initial = refreshCookie(signedIn);
            assertPersistence(signedIn, remember, 1_209_600);
            time.set(time.get().plusSeconds(60));
            var refreshed = mvc.perform(command("refresh").cookie(initial)).andExpect(status().isOk()).andReturn();
            var rotated = refreshCookie(refreshed);
            assertPersistence(refreshed, remember, 1_209_540);
            mvc.perform(command("logout").cookie(rotated)).andExpect(status().isNoContent());
        }
    }

    @Test
    void rotatesAndRevokesTheFamilyOnReuseWithoutRevokingAnotherLogin() throws Exception {
        String email = email();
        var registered = register(email);
        var separateLogin = mvc.perform(command("login").content(credentials(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();

        var fresh = mvc.perform(command("refresh").cookie(refreshCookie(registered)))
                .andExpect(status().isOk()).andReturn();
        assertNotEquals(refreshCookie(registered).getValue(), refreshCookie(fresh).getValue());
        assertNotEquals(access(registered), access(fresh));
        mvc.perform(me(access(fresh))).andExpect(status().isOk());
        mvc.perform(command("refresh").cookie(refreshCookie(registered))).andExpect(status().isUnauthorized());
        mvc.perform(me(access(fresh))).andExpect(status().isUnauthorized());
        mvc.perform(command("refresh").cookie(refreshCookie(fresh))).andExpect(status().isUnauthorized());
        mvc.perform(me(access(separateLogin))).andExpect(status().isOk());
    }

    @Test
    void serializesConcurrentRotations() throws Exception {
        var registered = register(email());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<MvcResult> refresh = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Las solicitudes no arrancaron juntas.");
                }
                return mvc.perform(command("refresh").cookie(refreshCookie(registered))).andReturn();
            };
            var first = threads.submit(refresh);
            var second = threads.submit(refresh);
            try {
                org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(200, 401), results.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
            var rotated = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
            mvc.perform(me(access(rotated))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rejectsExpiredAndAlteredTokensAndMixingAudiences() throws Exception {
        var registered = register(email());
        String token = access(registered);
        int signatureStart = token.lastIndexOf('.') + 1;
        String altered = token.substring(0, signatureStart) + (token.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + token.substring(signatureStart + 1);
        mvc.perform(me(altered)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SESSION"));
        mvc.perform(me(refreshCookie(registered).getValue())).andExpect(status().isUnauthorized());
        mvc.perform(command("refresh").cookie(new Cookie(COOKIE, token))).andExpect(status().isUnauthorized());
        time.set(START.plus(10, ChronoUnit.MINUTES));
        mvc.perform(me(token)).andExpect(status().isUnauthorized());
        var renewed = mvc.perform(command("refresh").cookie(refreshCookie(registered))).andExpect(status().isOk()).andReturn();
        mvc.perform(me(access(renewed))).andExpect(status().isOk());
        time.set(START.plus(14, ChronoUnit.DAYS));
        mvc.perform(command("refresh").cookie(refreshCookie(renewed))).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsForeignOriginsAndMissingCsrfAndRevokesOnLogout() throws Exception {
        var registered = register(email());
        for (String action : List.of("register", "login", "refresh", "logout")) {
            mvc.perform(post(AUTH + action).header(HttpHeaders.ORIGIN, "https://otro.example")
                            .header("X-Zonda-CSRF", "1").cookie(refreshCookie(registered)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
            mvc.perform(post(AUTH + action).header(HttpHeaders.ORIGIN, ORIGIN).cookie(refreshCookie(registered)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(me(access(registered))).andExpect(status().isOk());
        mvc.perform(command("logout").cookie(refreshCookie(registered))).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mvc.perform(command("logout")).andExpect(status().isNoContent());
        mvc.perform(me(access(registered))).andExpect(status().isUnauthorized());
        mvc.perform(command("refresh").cookie(refreshCookie(registered))).andExpect(status().isUnauthorized());
    }

    private MvcResult register(String email) throws Exception {
        return mvc.perform(command("register").content(credentials(email, PASSWORD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expiresIn").value(600))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andReturn();
    }

    @Test
    void documentsRegistrationAndBearerAccessInSwagger() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['201'].content").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['401'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].get.security[0].bearerAuth").isArray());
    }

    private static MockHttpServletRequestBuilder command(String action) {
        return post(AUTH + action).header(HttpHeaders.ORIGIN, ORIGIN).header("X-Zonda-CSRF", "1")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder me(String access) {
        return get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + access);
    }

    private static String access(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static Cookie refreshCookie(MvcResult result) {
        String firstPair = result.getResponse().getHeader(HttpHeaders.SET_COOKIE).split(";", 2)[0];
        return new Cookie(COOKIE, firstPair.substring(COOKIE.length() + 1));
    }

    private static void assertPersistence(MvcResult result, boolean remember, long maxAge) {
        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertEquals(remember, cookie.contains("; Max-Age="));
        assertEquals(remember, cookie.contains("; Expires="));
        if (remember) assertEquals(true, cookie.contains("; Max-Age=" + maxAge + ";"));
    }

    private static String credentials(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }
}
