package com.zondamarket.identity;

import com.jayway.jsonpath.JsonPath;
import com.zondamarket.identity.repository.IdentityEmailStore;
import com.zondamarket.identity.service.IdentityMailOutbox;
import com.zondamarket.shared.mail.MailSender;
import com.zondamarket.shared.mail.MailSender.DeliveryException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class IdentityEmailContractIT {
    private static final String AUTH = "/api/v1/auth/";
    private static final String VERIFY = "email-verification/";
    private static final String RESET = "password-reset/";
    private static final String ORIGIN = "http://127.0.0.1:3000";
    private static final String COOKIE = "__Host-zonda-refresh";
    private static final String OLD = "contraseña-ficticia-original";
    private static final String NEW = "contraseña-ficticia-nueva";
    private static final Instant START = Instant.parse("2026-01-02T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired IdentityMailOutbox outbox;
    @Autowired IdentityEmailStore store;
    @Autowired JdbcClient sql;
    @MockitoBean Clock clock;
    @MockitoBean MailSender mail;

    private record Attempt(UUID event, String recipient, String body) {
        String token() { return body.split("#token=")[1].lines().findFirst().orElseThrow(); }
    }

    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private final AtomicBoolean fail = new AtomicBoolean();

    @BeforeEach
    void setup() {
        sql.sql("truncate zonda_market.identity_mail_outbox, zonda_market.identity_email_token, "
                + "zonda_market.identity_email_rate").update();
        time.set(START);
        when(clock.instant()).thenAnswer(call -> time.get());
        when(mail.send(any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            attempts.add(new Attempt(id, call.getArgument(1), call.getArgument(3)));
            if (fail.get()) {
                throw new DeliveryException("Fallo transitorio ficticio.");
            }
            return "provider-" + id;
        });
    }

    @Test
    void verifiesOnlyTheAddressBoundToTheTokenAndRejectsReplays() throws Exception {
        String email = address();
        var owner = register(email);
        var other = register(address());
        assertEquals("", request(VERIFY, email.toUpperCase(java.util.Locale.ROOT), "192.0.2.10"));
        assertEquals("", request(VERIFY, address(), "192.0.2.10"));
        assertTrue(outbox.deliverNext());
        assertFalse(outbox.deliverNext());
        var mail = attempts.getFirst();
        assertEquals(email, mail.recipient());
        assertTrue(mail.body().contains("http://localhost:3000/verificar-correo#token="));
        assertNotEquals(mail.token(), sql.sql("select token_hash from zonda_market.identity_email_token")
                .query(String.class).single());

        mvc.perform(command(RESET + "confirm").content(reset(mail.token())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EMAIL_LINK"));
        mvc.perform(command(VERIFY + "confirm").content(token(mail.token())))
                .andExpect(status().isNoContent());
        mvc.perform(command(VERIFY + "confirm").content(token(mail.token())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EMAIL_LINK"));
        account(owner, true);
        account(other, false);
        request(VERIFY, email, "192.0.2.10");
        assertFalse(outbox.deliverNext());
    }

    @Test
    void resetsOnceUnderConcurrentUseAndRevokesEverySessionForThatAccount() throws Exception {
        String email = address();
        var first = register(email);
        var second = login(email, OLD);
        var other = register(address());
        assertEquals(request(RESET, email, "192.0.2.11"), request(RESET, address(), "192.0.2.11"));
        request(RESET, email, "192.0.2.11");
        assertTrue(outbox.deliverNext());
        assertTrue(outbox.deliverNext());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(0, 1).stream().map(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("La segunda confirmación no llegó a tiempo.");
                }
                return mvc.perform(command(RESET + "confirm").content(reset(attempts.get(index).token())))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int left = results.get(0).get(10, TimeUnit.SECONDS);
            int right = results.get(1).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(204, 400), java.util.stream.Stream.of(left, right).sorted().toList());
        }

        for (var session : List.of(first, second)) {
            mvc.perform(me(session)).andExpect(status().isUnauthorized());
            mvc.perform(browserCommand("refresh").cookie(session.getResponse().getCookie(COOKIE)))
                    .andExpect(status().isUnauthorized());
        }
        account(other, false);
        mvc.perform(browserCommand("login").content(credentials(email, OLD))).andExpect(status().isUnauthorized());
        account(login(email, NEW), false);
    }

    @Test
    void rejectsExpiredLinksWithoutChangingTheAccount() throws Exception {
        String email = address();
        register(email);
        request(RESET, email, "192.0.2.12");
        assertTrue(outbox.deliverNext());
        request(VERIFY, email, "192.0.2.12");
        assertTrue(outbox.deliverNext());

        time.set(START.plusSeconds(1800));
        mvc.perform(command(RESET + "confirm").content(reset(attempts.getFirst().token())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EMAIL_LINK"));
        time.set(START.plusSeconds(43200));
        mvc.perform(command(VERIFY + "confirm").content(token(attempts.getLast().token())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EMAIL_LINK"));
        account(login(email, OLD), false);
    }

    @Test
    void retriesTheSameMessageAfterFailureAndReclaimsAnAbandonedLease() throws Exception {
        String email = address();
        register(email);
        request(VERIFY, email, "192.0.2.13");
        var abandoned = store.claim(START, START.plusSeconds(60)).orElseThrow();
        assertFalse(outbox.deliverNext());
        time.set(START.plusSeconds(60));
        fail.set(true);
        assertTrue(outbox.deliverNext());
        assertFalse(outbox.deliverNext());
        assertEquals(0, sql.sql("select count(*) from zonda_market.identity_mail_outbox where sent_at is not null")
                .query(Integer.class).single());

        time.set(START.plusSeconds(70));
        fail.set(false);
        assertTrue(outbox.deliverNext());
        assertEquals(abandoned.id(), attempts.getFirst().event());
        assertEquals(attempts.getFirst(), attempts.getLast());
        assertEquals("provider-" + abandoned.id(), sql.sql("select provider_id from zonda_market.identity_mail_outbox")
                .query(String.class).single());
        assertFalse(outbox.deliverNext());
    }

    @Test
    void limitsRequestsByAddressAndByIpWithTheSameAcceptedResponse() throws Exception {
        String email = address();
        register(email);
        for (int i = 1; i <= 6; i++) {
            assertEquals("", request(VERIFY, email, "192.0.2." + i));
        }
        for (int i = 0; i < 30; i++) {
            assertEquals("", request(VERIFY, address(), "192.0.2.90"));
        }
        String blocked = address();
        register(blocked);
        assertEquals("", request(VERIFY, blocked, "192.0.2.90"));
        assertEquals(5, sql.sql("select count(*) from zonda_market.identity_mail_outbox")
                .query(Integer.class).single());
        time.set(START.plusSeconds(3600));
        assertEquals("", request(VERIFY, blocked, "192.0.2.90"));
        assertEquals(6, sql.sql("select count(*) from zonda_market.identity_mail_outbox")
                .query(Integer.class).single());
    }

    private String request(String purpose, String email, String ip) throws Exception {
        return mvc.perform(command(purpose + "request").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).content("""
                {"email":"%s"}
                """.formatted(email))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    private MvcResult register(String email) throws Exception {
        return mvc.perform(browserCommand("register").content(credentials(email, OLD)))
                .andExpect(status().isCreated()).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(browserCommand("login").content(credentials(email, password)))
                .andExpect(status().isOk()).andReturn();
    }

    private void account(MvcResult session, boolean verified) throws Exception {
        mvc.perform(me(session)).andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(verified));
    }

    private static MockHttpServletRequestBuilder me(MvcResult session) throws Exception {
        String access = JsonPath.read(session.getResponse().getContentAsString(), "$.accessToken");
        return get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access);
    }

    private static MockHttpServletRequestBuilder command(String action) {
        return post(AUTH + action).contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder browserCommand(String action) {
        return command(action).header(HttpHeaders.ORIGIN, ORIGIN).header("X-Zonda-CSRF", "1");
    }

    private static String address() { return UUID.randomUUID() + "@example.test"; }
    private static String token(String token) { return "{\"token\":\"" + token + "\"}"; }
    private static String reset(String token) {
        return """
                {"token":"%s","password":"%s"}
                """.formatted(token, NEW);
    }
    private static String credentials(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }
}
