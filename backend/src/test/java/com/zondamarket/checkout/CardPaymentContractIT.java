package com.zondamarket.checkout;

import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CardPaymentGateway;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({CheckoutContractIT.TimeConfiguration.class, CardPaymentContractIT.CardFixture.class})
class CardPaymentContractIT extends CheckoutContractFixtures {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired CheckoutContractIT.TestClock clock;
    @Autowired FakeCardGateway gateway;

    @BeforeEach
    void reset() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        gateway.creates.set(0);
        gateway.lookups.set(0);
        gateway.timeout = false;
        gateway.noCharge = false;
        gateway.answer = null;
        gateway.amountAdjustment = BigDecimal.ZERO;
        gateway.entered = new CountDownLatch(0);
        gateway.release = new CountDownLatch(0);
    }

    @Test
    void concurrentSubmissionsUseTheFrozenAmountAndApproveOnlyOnce() throws Exception {
        var purchase = reserve();
        var attempt = startCard(purchase, "first");
        var outsider = account(true);
        card(outsider, purchase.order(), attempt, "master", "submit").andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + purchase.order() + "/payments/" + attempt + "/outcome"),
                purchase.buyer()).content("{\"outcome\":\"APROBADO\"}")).andExpect(status().isNotFound());
        gateway.answer = Status.APROBADO;
        gateway.entered = new CountDownLatch(1);
        gateway.release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> card(purchase.buyer(), purchase.order(), attempt, "master", "submit")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROBADO")));
            try {
                assertTrue(gateway.entered.await(5, TimeUnit.SECONDS));
                card(purchase.buyer(), purchase.order(), attempt, "master", "submit").andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ENVIADO"));
                card(purchase.buyer(), purchase.order(), attempt, "master", "other").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
                assertStock(purchase.sku(), 1, 1);
            } finally {
                gateway.release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }

        card(purchase.buyer(), purchase.order(), attempt, "master", "submit").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROBADO"));
        assertEquals(new BigDecimal("19.25"), gateway.lastCharge.amount());
        assertEquals("AR-B", gateway.lastCharge.provinceCode());
        var persistedKey = sql.sql("SELECT provider_key FROM zonda_market.payment_attempt WHERE id = :id")
                .param("id", attempt).query(UUID.class).single();
        assertEquals(persistedKey, gateway.lastCharge.idempotencyKey());
        assertEquals(1, gateway.creates.get());
        assertEquals(1, gateway.lookups.get());
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
        assertStock(purchase.sku(), 1, 0);
        mvc.perform(auth(get("/api/v1/cart"), purchase.buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0));
    }

    @Test
    void followsTheReferenceAfterADeclinedOrUncertainDebitPayment() throws Exception {
        var purchase = reserve();
        var attempt = startCard(purchase, "first");
        gateway.timeout = true;
        card(purchase.buyer(), purchase.order(), attempt, "debvisa", "debit").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESULTADO_DESCONOCIDO"));
        consult(purchase, attempt).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESULTADO_DESCONOCIDO"));
        assertStock(purchase.sku(), 1, 1);
        mvc.perform(auth(post(ORDERS + purchase.order() + "/payments"), purchase.buyer())
                .header("Idempotency-Key", "second").content("{\"method\":\"CHECKOUT_PRO\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_ATTEMPT_ACTIVE"));

        gateway.answer = Status.APROBADO;
        gateway.amountAdjustment = BigDecimal.ONE;
        consult(purchase, attempt).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESULTADO_DESCONOCIDO"));
        assertStock(purchase.sku(), 1, 1);
        gateway.amountAdjustment = BigDecimal.ZERO;
        gateway.answer = Status.RECHAZADO;
        consult(purchase, attempt).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADO"))
                .andExpect(jsonPath("$.nextAction").value("ELEGIR_MEDIO"));
        assertEquals(1, gateway.creates.get());
        var replacement = startCard(purchase, "replacement");
        gateway.timeout = false;
        gateway.answer = Status.PENDIENTE;
        card(purchase.buyer(), purchase.order(), replacement, "debvisa", "new-debit").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE"));
        gateway.answer = Status.APROBADO;
        consult(purchase, replacement).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROBADO"));
        assertEquals(2, gateway.creates.get());
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
        assertStock(purchase.sku(), 1, 0);
    }

    @Test
    void aConcurrentLookupDoesNotHideAConfirmedFailureBeforeCharging() throws Exception {
        var purchase = reserve();
        var attempt = startCard(purchase, "first");
        gateway.noCharge = true;
        gateway.entered = new CountDownLatch(1);
        gateway.release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var submit = pool.submit(() -> card(purchase.buyer(), purchase.order(), attempt, "master", "submit")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECHAZADO")));
            try {
                assertTrue(gateway.entered.await(5, TimeUnit.SECONDS));
                consult(purchase, attempt).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("RESULTADO_DESCONOCIDO"));
            } finally {
                gateway.release.countDown();
            }
            submit.get(10, TimeUnit.SECONDS);
        }
        assertStock(purchase.sku(), 1, 1);
        assertEquals(0, eventCount(purchase.order(), "CHECKOUT_PAID"));
    }

    @Test
    void rejectsExpiredAndMalformedRequestsBeforeAnyProviderCall() throws Exception {
        var purchase = reserve();
        var attempt = startCard(purchase, "expires");
        mvc.perform(auth(post(ORDERS + purchase.order() + "/payments/card"), purchase.buyer())
                .header("Idempotency-Key", "invalid").content("""
                        {"attemptId":"%s","token":"123","paymentMethodId":"visa","installments":0}
                        """.formatted(attempt))).andExpect(status().isBadRequest());
        clock.set(clock.instant().plusSeconds(900));
        var fresh = login(purchase.buyer());
        card(fresh, purchase.order(), attempt, "master", "expired").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_INELIGIBLE"));
        assertEquals(0, gateway.creates.get());
        assertEquals(0, gateway.lookups.get());
    }

    private Purchase reserve() throws Exception {
        var buyer = account(true);
        var sku = product("Tarjeta ficticia", "19.25", "0.00", 2);
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "reserve").andExpect(status().isOk()).andReturn());
        mvc.perform(auth(get(ORDERS + order + "/payment-options"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.card.publicKey").value("publica-ficticia"))
                .andExpect(jsonPath("$.card.amount").value("19.25"));
        return new Purchase(buyer, sku, order);
    }

    private UUID startCard(Purchase purchase, String key) throws Exception {
        return uuid(mvc.perform(auth(post(ORDERS + purchase.order() + "/payments"), purchase.buyer())
                .header("Idempotency-Key", key).content("{\"method\":\"CARD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREADO"))
                .andExpect(jsonPath("$.nextAction").value("INGRESAR_TARJETA")).andReturn());
    }

    private ResultActions card(Account buyer, UUID order, UUID attempt, String method, String key) throws Exception {
        return mvc.perform(auth(post(ORDERS + order + "/payments/card"), buyer).header("Idempotency-Key", key)
                .content("""
                        {"attemptId":"%s","token":"token_ficticio_tarjeta","paymentMethodId":"%s","paymentType":"%s",
                         "installments":1,"payer":{"email":"comprador@example.test","identification":{"type":"DNI","number":"12345678"}},
                         "transactionAmount":1.00}
                        """.formatted(attempt, method, "debvisa".equals(method) ? "debit_card" : "credit_card")));
    }

    private ResultActions consult(Purchase purchase, UUID attempt) throws Exception {
        return mvc.perform(auth(post(ORDERS + purchase.order() + "/payments/" + attempt + "/reconcile-card"), purchase.buyer()));
    }

    private record Purchase(Account buyer, UUID sku, UUID order) {}

    @TestConfiguration
    static class CardFixture {
        @Bean FakeCardGateway cardGateway() { return new FakeCardGateway(); }
    }

    static class FakeCardGateway implements CardPaymentGateway {
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger lookups = new AtomicInteger();
        private final AtomicLong ids = new AtomicLong(1000L);
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private volatile Charge lastCharge;
        private volatile String lastId;
        private volatile Status answer;
        private volatile BigDecimal amountAdjustment;
        private volatile boolean timeout;
        private volatile boolean noCharge;

        @Override public String publicKey() { return "publica-ficticia"; }

        @Override
        public String create(Charge charge, CardPaymentRequest card) throws SubmissionUncertain, NoCharge {
            lastCharge = charge;
            creates.incrementAndGet();
            lastId = "ORD-ficticia-" + ids.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SubmissionUncertain();
            }
            if (timeout) throw new SubmissionUncertain();
            if (noCharge) throw new NoCharge();
            return lastId;
        }

        @Override
        public Optional<Observation> lookup(String paymentId, String reference, Instant expiresAt) {
            lookups.incrementAndGet();
            assertEquals(lastCharge.externalReference(), reference);
            if (paymentId != null) assertEquals(lastId, paymentId);
            return Optional.ofNullable(answer).map(status ->
                    new Observation(lastId, reference, lastCharge.amount().add(amountAdjustment), "ARS", status));
        }
    }
}
