package com.zondamarket.checkout;

import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CardPaymentGateway;
import com.zondamarket.checkout.service.CheckoutProGateway;
import com.zondamarket.checkout.service.PaymentProviderReadFailure;
import com.zondamarket.checkout.service.PaymentCompensationWorker;
import com.zondamarket.checkout.service.PaymentRefundGateway;
import com.zondamarket.checkout.service.PaymentReconciliationWorker;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false", "zonda.checkout.reconciliation.enabled=false",
        "zonda.mercado-pago.webhooks.pro-secret=pro-fixture-secret",
        "zonda.mercado-pago.webhooks.card-secret=card-fixture-secret"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({CheckoutContractIT.TimeConfiguration.class, PaymentWebhookContractIT.ProviderFixture.class})
class PaymentWebhookContractIT extends CheckoutContractFixtures {
    private static final String WEBHOOK = "/api/v1/webhooks/mercadopago";
    private static final BigDecimal TOTAL = new BigDecimal("19.25");
    private static final String CARD_ORDER_ID = "ORD01JYH1Z1YJN4HZ8J3Q0RB3YP6D";
    private static final String CARD_PAYMENT_ID = "PAY01JYH1Z1YJN4HZ8J3Q0RB3YP6E";
    private static final AtomicLong IDS = new AtomicLong(10_000);
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired CheckoutContractIT.TestClock clock;
    @Autowired PaymentReconciliationWorker worker;
    @Autowired PaymentCompensationWorker compensation;
    @Autowired BalanceFixture pro;
    @Autowired CardFixture card;
    @Autowired RefundFixture refunds;

    @BeforeEach
    void reset() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        sql.sql("DELETE FROM zonda_market.payment_webhook_inbox").update();
        sql.sql("UPDATE zonda_market.payment_attempt SET reconcile_after = now() + interval '1 day'").update();
        sql.sql("UPDATE zonda_market.payment_compensation SET next_attempt_at = now() + interval '1 day'").update();
        pro.payment = null;
        pro.failure = 0;
        pro.reads.set(0);
        card.order = null;
        refunds.sent.clear();
        refunds.history = null;
        refunds.timeoutAfterSending = false;
        refunds.timeoutBeforeSending = false;
    }

    @Test
    void acknowledgesBeforeProcessingAndSettlesOneTimeWithDuplicateAndOutOfOrderDeliveries() throws Exception {
        var purchase = startBalance();
        var paymentId = nextId();
        pro.payment = balance(purchase, paymentId, Status.APROBADO);
        for (int repeat = 0; repeat < 10; repeat++) {
            mvc.perform(notification("payment", paymentId, "11", "pro-fixture-secret")).andExpect(status().isOk());
        }
        assertEquals(1, inboxCount());
        assertEquals(0, pro.reads.get());
        assertStock(purchase.sku(), 1, 1);
        mvc.perform(auth(get(ORDERS + purchase.order()), account(true))).andExpect(status().isNotFound());

        mvc.perform(notification("payment", paymentId, "12", "pro-fixture-secret")).andExpect(status().isOk());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(worker::processNextNotification);
            var second = pool.submit(worker::processNextNotification);
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        pro.payment = balance(purchase, paymentId, Status.PENDIENTE);
        mvc.perform(notification("payment", paymentId, "13", "pro-fixture-secret")).andExpect(status().isOk());
        assertTrue(worker.processNextNotification());
        assertFalse(worker.processNextNotification());
        assertPaid(purchase, paymentId);

        var duplicatePayment = nextId();
        pro.payment = balance(purchase, duplicatePayment, Status.APROBADO);
        mvc.perform(notification("payment", duplicatePayment, "14", "pro-fixture-secret")).andExpect(status().isOk());
        assertTrue(worker.processNextNotification());
        assertEquals(2, transactions(purchase.attempt()));
        assertEquals(paymentId, settledPayment(purchase.order()));
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
    }

    @Test
    void rejectsInvalidSignaturesTopicsBodiesAndOversizeWithoutPersisting() throws Exception {
        var purchase = startBalance();
        var id = nextId();
        mvc.perform(notification("payment", id, "15", "wrong-secret")).andExpect(status().isUnauthorized());
        mvc.perform(notification("order", CARD_ORDER_ID, "16", "pro-fixture-secret")).andExpect(status().isUnauthorized());
        mvc.perform(notification("merchant_order", id, "17", "pro-fixture-secret")).andExpect(status().isBadRequest());
        mvc.perform(notification("payment", id, "18", "pro-fixture-secret").content(body("payment", "99999", "18")))
                .andExpect(status().isBadRequest());
        mvc.perform(notification("payment", id, "19", "pro-fixture-secret").content(" ".repeat(17_000)))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(0, inboxCount());
        assertFalse(worker.processNextNotification());
        assertEquals(0, pro.reads.get());
        assertStock(purchase.sku(), 1, 1);
    }

    @Test
    void backsOffFor429And503AndExposesPermanentProviderFailure() throws Exception {
        var purchase = startBalance();
        var id = nextId();
        mvc.perform(notification("payment", id, "20", "pro-fixture-secret")).andExpect(status().isOk());
        pro.failure = 429;
        assertTrue(worker.processNextNotification());
        assertEquals("PROVIDER_HTTP_429", failure("20", false));
        clock.set(clock.instant().plusSeconds(4));
        assertFalse(worker.processNextNotification());

        clock.set(clock.instant().plusSeconds(1));
        pro.failure = 503;
        assertTrue(worker.processNextNotification());
        assertEquals("PROVIDER_HTTP_503", failure("20", false));
        clock.set(clock.instant().plusSeconds(9));
        assertFalse(worker.processNextNotification());

        clock.set(clock.instant().plusSeconds(1));
        pro.failure = 401;
        assertTrue(worker.processNextNotification());
        assertEquals("PROVIDER_HTTP_401", failure("20", true));
        assertFalse(worker.processNextNotification());
        assertEquals(3, pro.reads.get());
        assertStock(purchase.sku(), 1, 1);
    }

    @Test
    void verifiesAnOrderNotificationUsingTheCardGatewayAndStoresTheTransactionId() throws Exception {
        var purchase = startCard();
        card.order = new CardPaymentGateway.Observation(CARD_ORDER_ID, CARD_PAYMENT_ID, purchase.reference(),
                TOTAL, "ARS", Status.APROBADO);
        mvc.perform(notification("order", CARD_ORDER_ID, "21", "card-fixture-secret")).andExpect(status().isOk());
        assertTrue(worker.processNextNotification());
        assertPaid(purchase, CARD_PAYMENT_ID);
        assertEquals(1, sql.sql("SELECT count(*) FROM zonda_market.payment_transaction WHERE provider_order_id = :order")
                .param("order", CARD_ORDER_ID).query(Integer.class).single());
    }

    @Test
    void settlesTwoSellersOncePerOrderForCardAndBalanceWithTheSameBuyer() throws Exception {
        var buyer = account(true);
        var outsider = account(true);
        var address = address(buyer);
        var first = product("Tienda Alfa", "100.25", "5.00", 3);
        var second = product("Tienda Beta", "250.90", "9.00", 3);
        var total = new BigDecimal("365.15");
        var baseline = metric("CARD", "approved");
        var balanceBaseline = metric("CHECKOUT_PRO", "approved");
        var providerOrder = "ORD01JYH1Z1YJN4HZ8J3Q0RB3YP65";
        var cardPaymentId = "PAY01JYH1Z1YJN4HZ8J3Q0RB3YP65";

        var cardOrder = reserve(buyer, address, first, second, 0, "CARD");
        submitCard(cardOrder, providerOrder);
        clock.set(clock.instant().plusSeconds(7));
        card.order = new CardPaymentGateway.Observation(providerOrder, cardPaymentId, cardOrder.reference(),
                total, "ARS", Status.APROBADO);
        for (String event : List.of("51", "52")) {
            mvc.perform(notification("order", providerOrder, event, "card-fixture-secret")).andExpect(status().isOk());
            assertTrue(worker.processNextNotification());
        }
        assertDualSettlement(cardOrder, outsider, "CARD", cardPaymentId);
        assertStock(first, 2, 0);
        assertStock(second, 2, 0);

        var balanceOrder = reserve(buyer, address, first, second, 3, "CHECKOUT_PRO");
        submitBalance(balanceOrder);
        var paymentId = nextId();
        pro.payment = new CheckoutProGateway.Payment(paymentId, balanceOrder.reference(), total, "ARS", Status.PENDIENTE);
        mvc.perform(notification("payment", paymentId, "53", "pro-fixture-secret")).andExpect(status().isOk());
        assertTrue(worker.processNextNotification());
        assertEquals(0, eventCount(balanceOrder.order(), "CHECKOUT_PAID"));
        assertStock(first, 1, 1);
        assertStock(second, 1, 1);

        clock.set(clock.instant().plusSeconds(9));
        pro.payment = new CheckoutProGateway.Payment(paymentId, balanceOrder.reference(), total, "ARS", Status.APROBADO);
        for (String event : List.of("54", "55")) {
            mvc.perform(notification("payment", paymentId, event, "pro-fixture-secret")).andExpect(status().isOk());
            assertTrue(worker.processNextNotification());
        }
        assertDualSettlement(balanceOrder, outsider, "CHECKOUT_PRO", paymentId);
        assertStock(first, 1, 0);
        assertStock(second, 1, 0);
        assertEquals(baseline + 1, metric("CARD", "approved"));
        assertEquals(balanceBaseline + 1, metric("CHECKOUT_PRO", "approved"));
    }

    @Test
    void measuresVerifiedRejectionOnceAndCreatesNoSettlement() throws Exception {
        var purchase = startBalance();
        var rejected = metric("CHECKOUT_PRO", "rejected");
        var paymentId = nextId();
        pro.payment = balance(purchase, paymentId, Status.RECHAZADO);
        clock.set(clock.instant().plusSeconds(5));
        for (String event : List.of("56", "57")) {
            mvc.perform(notification("payment", paymentId, event, "pro-fixture-secret")).andExpect(status().isOk());
            assertTrue(worker.processNextNotification());
        }
        assertEquals(rejected + 1, metric("CHECKOUT_PRO", "rejected"));
        assertEquals(0, settlementAmounts(purchase.order()).size());
    }

    private long metric(String method, String status) {
        return sql.sql("""
                SELECT COALESCE(sum(CASE WHEN :status = 'approved' THEN approved ELSE rejected END), 0)
                FROM zonda_market.payment_reconciliation_metrics
                WHERE method = :method
                """).param("status", status).param("method", method)
                .query(Long.class).single();
    }

    private void assertDualSettlement(Purchase purchase, Account outsider, String method, String paymentId) throws Exception {
        mvc.perform(auth(get(ORDERS + purchase.order()), purchase.buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"))
                .andExpect(jsonPath("$.total").value("365.15"))
                .andExpect(jsonPath("$.subOrders.length()").value(2))
                .andExpect(jsonPath("$.subOrders[0].status").value("PREPARANDO"))
                .andExpect(jsonPath("$.subOrders[1].status").value("PREPARANDO"));
        mvc.perform(auth(get(ORDERS + purchase.order()), outsider)).andExpect(status().isNotFound());
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
        assertEquals(1, transactions(purchase.attempt()));
        assertEquals(paymentId, settledPayment(purchase.order()));
        assertEquals(List.of("105.25", "259.90"), settlementAmounts(purchase.order()));
        assertTrue(sql.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM zonda_market.checkout_payment_outcome outcome
                    JOIN zonda_market.payment_transaction tx
                      ON (tx.method, tx.provider_payment_id) = (outcome.payment_method, outcome.provider_payment_id)
                    JOIN zonda_market.payment_attempt attempt ON attempt.id = tx.attempt_id
                    WHERE outcome.order_id = :order AND tx.method = :method
                      AND tx.trace_id = outcome.trace_id AND tx.verified_at > attempt.created_at
                )
                """).param("order", purchase.order()).param("method", method).query(Boolean.class).single());
    }

    private List<String> settlementAmounts(UUID order) {
        return sql.sql("""
                SELECT settlement.seller_net FROM zonda_market.checkout_sub_order_settlement settlement
                JOIN zonda_market.checkout_sub_order sub ON sub.id = settlement.sub_order_id
                WHERE sub.order_id = :order ORDER BY sub.seller_name
                """).param("order", order).query(BigDecimal.class).list().stream().map(BigDecimal::toPlainString).toList();
    }

    @Test
    void recoversPendingPaymentsUsingTheSweepWithNoWebhookDelivery() throws Exception {
        var purchase = startBalance();
        var id = nextId();
        pro.payment = new CheckoutProGateway.Payment(id, purchase.reference(), TOTAL.add(BigDecimal.ONE), "ARS",
                Status.APROBADO);
        assertFalse(worker.reconcileNextAttempt());
        clock.set(clock.instant().plusSeconds(15));
        assertTrue(worker.reconcileNextAttempt());
        assertStock(purchase.sku(), 1, 1);
        assertEquals(0, transactions(purchase.attempt()));
        pro.payment = balance(purchase, id, Status.APROBADO);
        assertFalse(worker.reconcileNextAttempt());
        clock.set(clock.instant().plusSeconds(30));
        assertTrue(worker.reconcileNextAttempt());
        assertEquals(0, inboxCount());
        assertPaid(purchase, id);
    }

    @Test
    void refundsALateCardPaymentAfterResaleAndRecoversALostResponse() throws Exception {
        String orderId = "ORD01JYH1Z1YJN4HZ8J3Q0RB3YP61";
        String paymentId = "PAY01JYH1Z1YJN4HZ8J3Q0RB3YP61";
        var original = startCard(orderId);
        clock.set(clock.instant().plusSeconds(901));
        var originalBuyer = login(original.buyer());
        mvc.perform(auth(get(ORDERS + original.order()), originalBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRADA"));
        var secondBuyer = account(true);
        add(secondBuyer, original.sku(), 2, 0);
        var quote = uuid(quote(secondBuyer, address(secondBuyer), 1).andExpect(status().isOk()).andReturn());
        var resale = uuid(confirm(secondBuyer, quote, "resale").andExpect(status().isOk()).andReturn());
        var reference = field(mvc.perform(auth(get(ORDERS + resale), secondBuyer)).andExpect(status().isOk()).andReturn(),
                "testPayment.reference");
        mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + resale + "/payment-outcome"), secondBuyer)
                .content("{\"reference\":\"" + reference + "\",\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAGADA"));
        assertStock(original.sku(), 0, 0);

        approveCard(original, orderId, paymentId, "31");
        approveCard(original, orderId, paymentId, "32");
        mvc.perform(auth(get(ORDERS + original.order()), originalBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.subOrders[0].status").value("CANCELADA"))
                .andExpect(jsonPath("$.refunds.length()").value(1))
                .andExpect(jsonPath("$.refunds[0].reason").value("TARDIO"))
                .andExpect(jsonPath("$.refunds[0].status").value("PENDIENTE"))
                .andExpect(jsonPath("$.refundedAmount").value("0.00"));
        mvc.perform(auth(get(ORDERS + original.order()), secondBuyer)).andExpect(status().isNotFound());
        assertEquals(0, eventCount(original.order(), "CHECKOUT_PAID"));
        assertTrue(settlementAmounts(original.order()).isEmpty());

        refunds.timeoutAfterSending = true;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(compensation::processNext);
            var second = pool.submit(compensation::processNext);
            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, refunds.sent.size());
        assertEquals(paymentId, refunds.sent.getFirst().paymentId());
        assertFalse(compensation.processNext());
        clock.set(clock.instant().plusSeconds(5));
        assertTrue(compensation.processNext());
        assertEquals(1, refunds.sent.size());
        mvc.perform(auth(get(ORDERS + original.order()), originalBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRADA"))
                .andExpect(jsonPath("$.refunds[0].status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.refundedAmount").value("19.25"));
        assertStock(original.sku(), 0, 0);
    }

    @Test
    void refundsOnlyTheExtraCardChargeAndRetriesWithThePersistedKey() throws Exception {
        String validOrder = "ORD01JYH1Z1YJN4HZ8J3Q0RB3YP62";
        String validPayment = "PAY01JYH1Z1YJN4HZ8J3Q0RB3YP62";
        String extraOrder = "ORD01JYH1Z1YJN4HZ8J3Q0RB3YP63";
        String extraPayment = "PAY01JYH1Z1YJN4HZ8J3Q0RB3YP63";
        var purchase = startCard(validOrder);
        approveCard(purchase, validOrder, validPayment, "33");
        approveCard(purchase, validOrder, validPayment, "34");
        assertFalse(compensation.processNext());
        approveCard(purchase, extraOrder, extraPayment, "35");
        approveCard(purchase, extraOrder, extraPayment, "36");

        refunds.timeoutBeforeSending = true;
        assertTrue(compensation.processNext());
        refunds.timeoutBeforeSending = false;
        clock.set(clock.instant().plusSeconds(5));
        assertTrue(compensation.processNext());
        assertFalse(compensation.processNext());
        assertEquals(2, refunds.sent.size());
        assertEquals(refunds.sent.getFirst(), refunds.sent.getLast());
        assertEquals(extraOrder, refunds.sent.getFirst().orderId());
        assertEquals(extraPayment, refunds.sent.getFirst().paymentId());
        assertEquals(validPayment, settledPayment(purchase.order()));
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
        assertEquals(List.of("19.25"), settlementAmounts(purchase.order()));
        assertStock(purchase.sku(), 1, 0);
        var seller = login(sql.sql("""
                SELECT account.id, account.email FROM zonda_market.listing_sku sku
                JOIN zonda_market.listing listing ON listing.id = sku.listing_id
                JOIN zonda_market.seller_profile profile ON profile.id = listing.seller_id
                JOIN zonda_market.user_account account ON account.id = profile.account_id
                WHERE sku.id = :sku
                """).param("sku", purchase.sku()).query((row, i) ->
                    new Account(row.getObject("id", UUID.class), null, row.getString("email"))).single());
        mvc.perform(auth(get("/api/v1/seller/metrics"), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value("19.25"))
                .andExpect(jsonPath("$.refunds").value("0.00"))
                .andExpect(jsonPath("$.net").value("19.25"));
        mvc.perform(auth(get(ORDERS + purchase.order()), purchase.buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"))
                .andExpect(jsonPath("$.subOrders[0].status").value("PREPARANDO"))
                .andExpect(jsonPath("$.refunds.length()").value(1))
                .andExpect(jsonPath("$.refunds[0].reason").value("DUPLICADO"))
                .andExpect(jsonPath("$.refunds[0].status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.refundedAmount").value("19.25"));
    }

    private void approveCard(Purchase purchase, String orderId, String paymentId, String event) throws Exception {
        card.order = new CardPaymentGateway.Observation(orderId, paymentId, purchase.reference(),
                TOTAL, "ARS", Status.APROBADO);
        mvc.perform(notification("order", orderId, event, "card-fixture-secret")).andExpect(status().isOk());
        assertTrue(worker.processNextNotification());
    }

    private Purchase startBalance() throws Exception {
        var purchase = reserve("CHECKOUT_PRO");
        submitBalance(purchase);
        return purchase;
    }

    private void submitBalance(Purchase purchase) throws Exception {
        mvc.perform(auth(post(ORDERS + purchase.order() + "/payments/pro"), purchase.buyer())
                .header("Idempotency-Key", "submit")
                .content("{\"attemptId\":\"" + purchase.attempt() + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE"));
    }

    private Purchase startCard() throws Exception {
        return startCard(CARD_ORDER_ID);
    }

    private Purchase startCard(String orderId) throws Exception {
        var purchase = reserve("CARD");
        submitCard(purchase, orderId);
        return purchase;
    }

    private void submitCard(Purchase purchase, String orderId) throws Exception {
        card.nextOrderId = orderId;
        mvc.perform(auth(post(ORDERS + purchase.order() + "/payments/card"), purchase.buyer())
                .header("Idempotency-Key", "submit").content("""
                        {"attemptId":"%s","token":"token_ficticio_tarjeta","paymentMethodId":"visa",
                         "paymentType":"credit_card","installments":1,"payer":{"email":"comprador@example.test"}}
                        """.formatted(purchase.attempt()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESULTADO_DESCONOCIDO"));
    }

    private Purchase reserve(String method) throws Exception {
        var buyer = account(true);
        var sku = product("Webhook ficticio", TOTAL.toPlainString(), "0.00", 2);
        return reserve(buyer, address(buyer), sku, null, 0, method);
    }

    private Purchase reserve(Account buyer, UUID address, UUID sku, UUID second, long version, String method)
            throws Exception {
        add(buyer, sku, 1, version++);
        if (second != null) add(buyer, second, 1, version++);
        var quote = uuid(quote(buyer, address, version).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "reserve-" + quote).andExpect(status().isOk()).andReturn());
        var attempt = uuid(mvc.perform(auth(post(ORDERS + order + "/payments"), buyer)
                .header("Idempotency-Key", "start").content("{\"method\":\"" + method + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return new Purchase(buyer, order, sku, attempt);
    }

    private void assertPaid(Purchase purchase, String paymentId) throws Exception {
        mvc.perform(auth(get(ORDERS + purchase.order()), purchase.buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"));
        mvc.perform(auth(get("/api/v1/cart"), purchase.buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0));
        assertStock(purchase.sku(), 1, 0);
        assertEquals(1, eventCount(purchase.order(), "CHECKOUT_PAID"));
        assertEquals(1, transactions(purchase.attempt()));
        assertEquals(paymentId, settledPayment(purchase.order()));
        assertEquals("APROBADO", sql.sql("SELECT status FROM zonda_market.payment_transaction WHERE attempt_id = :id")
                .param("id", purchase.attempt()).query(String.class).single());
    }

    private String settledPayment(UUID order) {
        return sql.sql("SELECT provider_payment_id FROM zonda_market.checkout_payment_outcome WHERE order_id = :order")
                .param("order", order).query(String.class).single();
    }

    private int inboxCount() {
        return sql.sql("SELECT count(*) FROM zonda_market.payment_webhook_inbox").query(Integer.class).single();
    }

    private int transactions(UUID attempt) {
        return sql.sql("SELECT count(*) FROM zonda_market.payment_transaction WHERE attempt_id = :id")
                .param("id", attempt).query(Integer.class).single();
    }

    private String failure(String event, boolean finished) {
        return sql.sql("""
                SELECT failure_code FROM zonda_market.payment_webhook_inbox
                WHERE notification_id = :event AND (finished_at IS NOT NULL) = :finished
                """).param("event", event).param("finished", finished).query(String.class).single();
    }

    private MockHttpServletRequestBuilder notification(String topic, String id, String event, String secret) throws Exception {
        var request = UUID.randomUUID().toString();
        var time = Long.toString(clock.instant().toEpochMilli());
        var manifest = "id:" + id + ";request-id:" + request + ";ts:" + time + ";";
        var hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = HexFormat.of().formatHex(hmac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        return post(WEBHOOK).queryParam("data.id", id).queryParam("type", topic)
                .header("x-request-id", request).header("x-signature", "ts=" + time + ",v1=" + hash)
                .contentType(MediaType.APPLICATION_JSON).content(body(topic, id, event));
    }

    private static String body(String topic, String id, String event) {
        return """
                {"id":%s,"type":"%s","data":{"id":"%s","status":"approved"}}
                """.formatted(event, topic, id);
    }

    private static String nextId() { return Long.toString(IDS.incrementAndGet()); }

    private static CheckoutProGateway.Payment balance(Purchase purchase, String id, Status status) {
        return new CheckoutProGateway.Payment(id, purchase.reference(), TOTAL, "ARS", status);
    }

    private record Purchase(Account buyer, UUID order, UUID sku, UUID attempt) {
        String reference() { return "zonda-" + attempt; }
    }

    @TestConfiguration
    static class ProviderFixture {
        @Bean BalanceFixture checkoutProGateway() { return new BalanceFixture(); }
        @Bean CardFixture cardPaymentGateway() { return new CardFixture(); }
        @Bean RefundFixture refundGateway() { return new RefundFixture(); }
    }

    static class BalanceFixture implements CheckoutProGateway {
        private final AtomicInteger reads = new AtomicInteger();
        private volatile int failure;
        private volatile Payment payment;

        @Override public Preference create(Checkout checkout) {
            return new Preference(checkout.reference(), "https://www.mercadopago.com.ar/checkout/v1/redirect");
        }

        @Override public Optional<Preference> recover(Checkout checkout) { return Optional.empty(); }
        @Override public Optional<Payment> lookup(Checkout checkout) { return Optional.ofNullable(payment); }

        @Override public Optional<Payment> lookupPayment(String id) throws PaymentProviderReadFailure {
            reads.incrementAndGet();
            if (failure != 0) throw new PaymentProviderReadFailure(failure);
            return Optional.ofNullable(payment).filter(found -> id.equals(found.id()));
        }
    }

    static class CardFixture implements CardPaymentGateway {
        private volatile Observation order;
        private volatile String nextOrderId;

        @Override public String publicKey() { return "fixture"; }
        @Override public String create(Charge charge, CardPaymentRequest request) { return nextOrderId; }
        @Override public Optional<Observation> lookup(String id, String reference, Instant expires) {
            return lookupOrder(id);
        }
        @Override public Optional<Observation> lookupOrder(String id) {
            return Optional.ofNullable(order).filter(found -> found.providerOrderId().equals(id));
        }
    }

    static class RefundFixture implements PaymentRefundGateway {
        private final CopyOnWriteArrayList<Charge> sent = new CopyOnWriteArrayList<>();
        private volatile Observation history;
        private volatile boolean timeoutBeforeSending;
        private volatile boolean timeoutAfterSending;

        @Override public Method refundMethod() { return Method.CARD; }
        @Override public Observation inspect(Charge charge) {
            return history == null ? new Observation(State.AVAILABLE, null) : history;
        }
        @Override public Observation refund(Charge charge) throws PaymentProviderReadFailure {
            sent.add(charge);
            if (timeoutBeforeSending) throw new PaymentProviderReadFailure(0);
            history = new Observation(State.CONFIRMED, "REF-" + charge.paymentId());
            if (timeoutAfterSending) throw new PaymentProviderReadFailure(0);
            return history;
        }
    }
}
