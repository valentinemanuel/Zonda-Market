package com.zondamarket.checkout;

import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CheckoutProGateway;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        "zonda.checkout.expiry.enabled=false", "zonda.checkout.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({CheckoutContractIT.TimeConfiguration.class, CheckoutProContractIT.ProFixture.class})
class CheckoutProContractIT extends CheckoutContractFixtures {
    private static final String URL = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=";
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired CheckoutContractIT.TestClock clock;
    @Autowired FakeProGateway gateway;

    @BeforeEach
    void reset() {
        clock.set(Instant.now());
        gateway.creates.set(0);
        gateway.checkout = null;
        gateway.payment = null;
        gateway.recoverable = false;
        gateway.release = new CountDownLatch(0);
        gateway.entered = new CountDownLatch(0);
    }

    @Test
    void savesOnePreferenceUnderConcurrentSubmissionsAndChecksBuyerOwnership() throws Exception {
        var purchase = reserve();
        var outsider = account(true);
        submit(purchase, outsider, "same").andExpect(status().isNotFound());
        mvc.perform(auth(post(consultPath(purchase)), outsider)).andExpect(status().isNotFound());
        gateway.entered = new CountDownLatch(1);
        gateway.release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> submit(purchase, purchase.buyer(), "same")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.checkoutUrl").value(URL + purchase.attempt())));
            try {
                assertTrue(gateway.entered.await(5, TimeUnit.SECONDS));
                submit(purchase, purchase.buyer(), "same").andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ENVIADO"));
                submit(purchase, purchase.buyer(), "different").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
            } finally {
                gateway.release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        submit(purchase, purchase.buyer(), "same").andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value(URL + purchase.attempt()));
        assertEquals(new BigDecimal("19.25"), gateway.checkout.amount());
        assertEquals("ARS", gateway.checkout.currency());
        assertEquals("zonda-" + purchase.attempt(), gateway.checkout.reference());
        assertEquals(1, gateway.creates.get());
        assertStock(purchase.sku(), 1, 1);
    }

    @Test
    void recoversWithoutCreatingAgainAndSettlesOnlyAfterAVerifiedApproval() throws Exception {
        var purchase = reserve();
        gateway.recoverable = true;
        submit(purchase, purchase.buyer(), "same").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESULTADO_DESCONOCIDO"));
        mvc.perform(auth(post(ORDERS + purchase.order() + "/payments"), purchase.buyer())
                .header("Idempotency-Key", "another").content("{\"method\":\"CARD\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_ATTEMPT_ACTIVE"));

        consult(purchase, "?status=approved&payment_id=999").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE"))
                .andExpect(jsonPath("$.checkoutUrl").value(URL + purchase.attempt()));
        gateway.payment = new CheckoutProGateway.Payment("123", gateway.checkout.reference(),
                BigDecimal.ONE, "ARS", Status.APROBADO);
        consult(purchase, "").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDIENTE"));
        assertStock(purchase.sku(), 1, 1);
        assertEquals(0, eventCount(purchase.order(), "CHECKOUT_PAID"));

        gateway.payment = new CheckoutProGateway.Payment("123", gateway.checkout.reference(),
                gateway.checkout.amount(), "ARS", Status.APROBADO);
        consult(purchase, "").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROBADO"));
        consult(purchase, "").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROBADO"));
        assertEquals(1, gateway.creates.get());
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
        assertStock(purchase.sku(), 1, 0);
        mvc.perform(auth(get("/api/v1/cart"), purchase.buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0));
    }

    private Purchase reserve() throws Exception {
        var buyer = account(true);
        var sku = product("Saldo ficticio", "19.25", "0.00", 2);
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "reserve").andExpect(status().isOk()).andReturn());
        mvc.perform(auth(get(ORDERS + order + "/payment-options"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutPro").value(true));
        var attempt = uuid(mvc.perform(auth(post(ORDERS + order + "/payments"), buyer)
                .header("Idempotency-Key", "start").content("{\"method\":\"CHECKOUT_PRO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextAction").value("ABRIR_CHECKOUT_PRO")).andReturn());
        return new Purchase(buyer, order, sku, attempt);
    }

    private ResultActions submit(Purchase purchase, Account buyer, String key) throws Exception {
        return mvc.perform(auth(post(ORDERS + purchase.order() + "/payments/pro"), buyer)
                .header("Idempotency-Key", key).content("{\"attemptId\":\"" + purchase.attempt() + "\"}"));
    }

    private ResultActions consult(Purchase purchase, String query) throws Exception {
        return mvc.perform(auth(post(consultPath(purchase) + query), purchase.buyer()));
    }

    private static String consultPath(Purchase purchase) {
        return ORDERS + purchase.order() + "/payments/" + purchase.attempt() + "/reconcile-pro";
    }

    private record Purchase(Account buyer, UUID order, UUID sku, UUID attempt) {}

    @TestConfiguration
    static class ProFixture {
        @Bean FakeProGateway checkoutProGateway() { return new FakeProGateway(); }
    }

    static class FakeProGateway implements CheckoutProGateway {
        private final AtomicInteger creates = new AtomicInteger();
        private volatile Checkout checkout;
        private volatile Payment payment;
        private volatile boolean recoverable;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        @Override
        public Preference create(Checkout checkout) throws Uncertain {
            this.checkout = checkout;
            creates.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new Uncertain();
            }
            if (recoverable) throw new Uncertain();
            return preference(checkout);
        }

        @Override public Optional<Preference> recover(Checkout checkout) {
            assertEquals(this.checkout, checkout);
            return recoverable ? Optional.of(preference(checkout)) : Optional.empty();
        }

        @Override public Optional<Payment> lookup(Checkout checkout) {
            assertEquals(this.checkout, checkout);
            return Optional.ofNullable(payment);
        }

        @Override public Optional<Payment> lookupPayment(String id) { return Optional.empty(); }

        private static Preference preference(Checkout checkout) {
            var id = checkout.reference().substring("zonda-".length());
            return new Preference(id, URL + id);
        }
    }
}
