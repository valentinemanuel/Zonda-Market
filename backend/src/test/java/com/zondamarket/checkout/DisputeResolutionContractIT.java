package com.zondamarket.checkout;

import com.jayway.jsonpath.JsonPath;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.service.DisputeRefundWorker;
import com.zondamarket.checkout.service.LocalPaymentPort;
import com.zondamarket.checkout.service.PaymentProviderReadFailure;
import com.zondamarket.checkout.service.PaymentRefundGateway;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.reconciliation.enabled=false", "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(DisputeResolutionContractIT.RefundConfiguration.class)
class DisputeResolutionContractIT extends CheckoutContractFixtures {
    private static final String ADMIN = "/api/v1/admin/disputes/";
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired DisputeRefundWorker worker;
    @Autowired RefundFixture gateway;

    @BeforeEach
    void resetGateway() {
        gateway.refunds.clear();
        gateway.sends = 0;
        gateway.loseReply = false;
        gateway.reject = false;
        gateway.revealHistory = false;
    }

    @Test
    void reservesARefundThroughATimeoutAndReconcilesTheOriginalKeyAndSellerNet() throws Exception {
        var buyer = account(true);
        var seller = account(true);
        var otherSeller = account(true);
        var admin = admin();
        sql.sql("UPDATE zonda_market.category SET commission_bps = 1000 WHERE id = :id").param("id", CATEGORY).update();
        var order = paid(buyer, product(seller, "Alfa parcial", "50.00", "10.00", 3),
                product(otherSeller, "Beta logística", "40.00", "0", 3));
        externalize(order.id());
        var dispute = mediate(buyer, admin, order.subs().getFirst());
        var key = UUID.randomUUID();

        mvc.perform(auth(get(ADMIN + dispute + "/resolution-balance"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value("60.00")).andExpect(jsonPath("$.paymentAvailable").value("100.00"));
        resolve(seller, dispute, key, "REEMBOLSO", "25.00", 1).andExpect(status().isForbidden());
        resolve(admin, dispute, key, "REEMBOLSO", "61.00", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_BALANCE_EXCEEDED"));
        assertEquals(0, gateway.sends);

        gateway.loseReply = true;
        UUID refund = uuid(resolve(admin, dispute, key, "REEMBOLSO", "25.00", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE")).andReturn());
        assertTrue(worker.processNext());
        detail(buyer, dispute, "REEMBOLSO_PENDIENTE", "PENDIENTE");
        mvc.perform(auth(get(ADMIN + dispute + "/resolution-balance"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAvailable").value("75.00"));
        assertEquals(0, countAdjustment(refund));
        due(refund);
        assertTrue(worker.processNext());
        assertEquals(2, gateway.sends);
        assertEquals(1, gateway.refunds.size());
        detail(buyer, dispute, "CERRADA_REEMBOLSADA", "CONFIRMADA");
        resolve(admin, dispute, key, "REEMBOLSO", "25.00", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.providerRefundId").value("fixture-" + refund));
        resolve(admin, dispute, key, "REEMBOLSO", "26.00", 1).andExpect(status().isConflict());
        assertEquals(1, countAdjustment(refund));
        assertEquals("25.00|0.00|2.50", sql.sql("""
                SELECT concat_ws('|', merchandise_refunded, shipping_refunded, commission_reversed)
                FROM zonda_market.checkout_sub_order_refund WHERE refund_id = :id
                """).param("id", refund).query(String.class).single());
        mvc.perform(auth(get(ORDERS + order.id()), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REEMBOLSO_PARCIAL"))
                .andExpect(jsonPath("$.subOrders[0].status").value("PREPARANDO"));
        mvc.perform(auth(post("/api/v1/seller/sub-orders/" + order.subs().getLast() + "/ship"), otherSeller).content("""
                {"carrier":"ANDREANI","trackingCode":"E604-OTRA","expectedVersion":0}
                """)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/seller/metrics"), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.refunds").value("25.00")).andExpect(jsonPath("$.net").value("32.50"));
    }

    @Test
    void serializesTwoSubOrdersOnTheSameCaptureAndRefundsTheFullOrder() throws Exception {
        var buyer = account(true);
        var admin = admin();
        var order = paid(buyer, product("Alfa total", "40.00", "10.00", 2), product("Beta total", "50.00", "0", 2));
        externalize(order.id());
        var first = mediate(buyer, admin, order.subs().getFirst());
        var second = mediate(buyer, admin, order.subs().getLast());
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return resolve(admin, first, UUID.randomUUID(), "REEMBOLSO", "50.00", 1); });
            var b = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return resolve(admin, second, UUID.randomUUID(), "REEMBOLSO", "50.00", 1); });
            a.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
            b.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
        }
        mvc.perform(auth(get(ADMIN + first + "/resolution-balance"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAvailable").value("0.00"));
        gateway.loseReply = true;
        assertTrue(worker.processNext());
        assertEquals(1, gateway.sends);
        assertFalse(worker.processNext());
        due(gateway.refunds.keySet().iterator().next());
        assertTrue(worker.processNext());
        assertEquals(2, gateway.sends);
        assertTrue(worker.processNext());
        assertEquals(3, gateway.sends);
        assertEquals(2, gateway.refunds.size());
        mvc.perform(auth(get(ORDERS + order.id()), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REEMBOLSADA"))
                .andExpect(jsonPath("$.subOrders[0].status").value("REEMBOLSADA"))
                .andExpect(jsonPath("$.subOrders[1].status").value("REEMBOLSADA"));
        assertEquals(new BigDecimal("100.00"), sql.sql("""
                SELECT sum(r.merchandise_refunded + r.shipping_refunded) FROM zonda_market.checkout_sub_order_refund r
                JOIN zonda_market.checkout_sub_order sub ON r.sub_order_id = sub.id WHERE sub.order_id = :id
                """).param("id", order.id()).query(BigDecimal.class).single());
    }

    @Test
    void correlatesADisputeAndRecoversAnExistingRefundByReadOnlyLookup() throws Exception {
        var buyer = account(true);
        var admin = admin();
        var order = paid(buyer, product("Auditoría", "30.00", "0", 2));
        externalize(order.id());
        var dispute = mediate(buyer, admin, order.subs().getFirst());
        gateway.loseReply = true;
        var refund = uuid(resolve(admin, dispute, UUID.randomUUID(), "REEMBOLSO", "15.00", 1)
                .andExpect(status().isOk()).andReturn());
        assertTrue(worker.processNext());
        assertEquals(1, gateway.sends);
        // Model a work item whose provider response remained uncertain past the automatic send window.
        sql.sql("UPDATE zonda_market.dispute_resolution SET status = 'EN_REVISION' WHERE id = :id")
                .param("id", refund).update();
        var auditPath = "/api/v1/admin/audit-logs";
        mvc.perform(auth(get(auditPath), buyer)).andExpect(status().isForbidden());
        mvc.perform(auth(get(auditPath), admin).param("from", "2026-11-22").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUDIT_INVALID_RANGE"));

        var recovery = "/api/v1/admin/payment-incidents/" + refund + "/reconcile";
        var key = UUID.randomUUID();
        var reason = """
                {"reason":"Motivo privado ficticio"}
                """;
        var accepted = mvc.perform(auth(post(recovery), admin).header("Idempotency-Key", key).content(reason))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        // The first manual observation is still uncertain. It must never call the refund write.
        assertTrue(worker.processNext());
        assertEquals(1, gateway.sends);
        assertEquals(0, countAdjustment(refund));
        due(refund);
        gateway.revealHistory = true;
        assertTrue(worker.processNext());
        assertEquals(1, gateway.sends);
        assertEquals(1, countAdjustment(refund));
        detail(buyer, dispute, "CERRADA_REEMBOLSADA", "CONFIRMADA");
        assertEquals(accepted, mvc.perform(auth(post(recovery), admin).header("Idempotency-Key", key).content(reason))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        var events = mvc.perform(auth(get(auditPath), admin).param("resource", dispute.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].action", hasItems(
                        "DISPUTE_OPENED", "DISPUTE_ASSIGNED", "DISPUTE_DECIDED", "INCIDENT_RECONCILE_REQUESTED",
                        "DISPUTE_REFUND_STATUS")))
                .andExpect(jsonPath("$.items[*].result", hasItems("EN_REVISION", "CONFIRMADA")))
                .andReturn().getResponse().getContentAsString();
        for (var privateValue : List.of("Datos ficticios", "Decisión ficticia", "Motivo privado ficticio", "fixture-" + refund)) {
            assertFalse(events.contains(privateValue));
        }
        mvc.perform(auth(get(auditPath), admin).param("actor", admin.id().toString()).param("action", "DISPUTE_DECIDED")
                        .param("resource", dispute.toString()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void releasesARejectedReserveAndThenClosesTheDisputeWithoutMoneyMovement() throws Exception {
        var buyer = account(true);
        var admin = admin();
        var order = paid(buyer, product("Rechazo", "10.00", "0", 2));
        externalize(order.id());
        var dispute = mediate(buyer, admin, order.subs().getFirst());
        gateway.reject = true;
        UUID refund = uuid(resolve(admin, dispute, UUID.randomUUID(), "REEMBOLSO", "10.00", 1)
                .andExpect(status().isOk()).andReturn());
        assertTrue(worker.processNext());
        detail(buyer, dispute, "EN_MEDIACION", "RECHAZADA");
        assertEquals(0, countAdjustment(refund));
        mvc.perform(auth(get(ADMIN + dispute + "/resolution-balance"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value("10.00"));
        resolve(admin, dispute, UUID.randomUUID(), "SIN_REEMBOLSO", null, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_VERSION_CONFLICT"));
        resolve(admin, dispute, UUID.randomUUID(), "SIN_REEMBOLSO", null, 3).andExpect(status().isOk());
        detail(buyer, dispute, "CERRADA_SIN_REEMBOLSO", "CERRADA");
        mvc.perform(auth(get(ORDERS + order.id()), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"));
    }

    private void detail(Account actor, UUID dispute, String state, String resolution) throws Exception {
        mvc.perform(auth(get("/api/v1/disputes/" + dispute), actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(state)).andExpect(jsonPath("$.resolution.status").value(resolution));
    }

    private void due(UUID id) {
        sql.sql("UPDATE zonda_market.dispute_resolution SET next_attempt_at = now() - interval '1 second' WHERE id = :id")
                .param("id", id).update();
    }

    private int countAdjustment(UUID id) {
        return sql.sql("SELECT count(*) FROM zonda_market.checkout_sub_order_refund WHERE refund_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private Account admin() throws Exception {
        var admin = account(true);
        sql.sql("UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE id = :id").param("id", admin.id()).update();
        return admin;
    }

    private Purchase paid(Account buyer, UUID... skus) throws Exception {
        for (int i = 0; i < skus.length; i++) add(buyer, skus[i], 1, i);
        var quote = uuid(quote(buyer, address(buyer), skus.length).andExpect(status().isOk()).andReturn());
        var saved = confirm(buyer, quote, quote.toString()).andExpect(status().isOk()).andReturn();
        var order = uuid(saved);
        mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + order + "/payment-outcome"), buyer).content("""
                {"reference":"%s","outcome":"APPROVED"}
                """.formatted(LocalPaymentPort.reference(order)))).andExpect(status().isOk());
        List<String> subs = JsonPath.read(saved.getResponse().getContentAsString(), "$.subOrders[*].id");
        return new Purchase(order, subs.stream().map(UUID::fromString).toList());
    }

    private UUID mediate(Account buyer, Account admin, UUID sub) throws Exception {
        var id = uuid(mvc.perform(auth(post("/api/v1/me/sub-orders/" + sub + "/disputes"), buyer).content("""
                {"reason":"Consulta","description":"Datos ficticios"}
                """)).andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(post(ADMIN + id + "/assign"), admin)).andExpect(status().isOk());
        return id;
    }

    private ResultActions resolve(Account actor, UUID dispute, UUID key, String outcome, String amount, int version) throws Exception {
        return mvc.perform(auth(post(ADMIN + dispute + "/resolve"), actor).header("Idempotency-Key", key).content("""
                {"outcome":"%s","amount":%s,"reason":"Decisión ficticia","expectedVersion":%d}
                """.formatted(outcome, amount, version)));
    }

    private void externalize(UUID order) {
        var attempt = UUID.randomUUID();
        sql.sql("""
                INSERT INTO zonda_market.payment_attempt (id, order_id, method, status, request_key, provider_key,
                    external_reference, amount, currency, expires_at, trace_id, created_at, updated_at, provider, provider_order_id)
                SELECT :attempt, id, 'CARD', 'APROBADO', :reference, :attempt, :reference, total, currency,
                    expires_at, trace_id, now(), now(), 'MERCADO_PAGO', :reference
                FROM zonda_market.checkout_order WHERE id = :order
                """).param("attempt", attempt).param("order", order).param("reference", order.toString()).update();
        sql.sql("""
                INSERT INTO zonda_market.payment_transaction (method, provider_payment_id, provider_order_id, attempt_id,
                    status, amount, currency, trace_id, verified_at)
                SELECT method, external_reference, provider_order_id, id, 'APROBADO', amount, currency, trace_id, now()
                FROM zonda_market.payment_attempt WHERE id = :attempt
                """).param("attempt", attempt).update();
        sql.sql("""
                UPDATE zonda_market.checkout_payment_outcome SET reference = :reference, provider_payment_id = :reference,
                    payment_method = 'CARD' WHERE order_id = :order
                """).param("reference", order.toString()).param("order", order).update();
    }

    private record Purchase(UUID id, List<UUID> subs) {}

    @TestConfiguration
    static class RefundConfiguration {
        @Bean RefundFixture disputeGateway() { return new RefundFixture(); }
    }

    static class RefundFixture implements PaymentRefundGateway {
        final Map<UUID, Observation> refunds = new LinkedHashMap<>();
        int sends;
        boolean loseReply;
        boolean reject;
        boolean revealHistory;

        @Override public Method refundMethod() { return Method.CARD; }
        @Override public Observation inspect(Charge charge) { throw new AssertionError(); }
        @Override public Observation refund(Charge charge) { throw new AssertionError(); }
        @Override public Observation inspectPartial(PartialCharge request) {
            if (revealHistory && refunds.containsKey(request.refund().key())) return refunds.get(request.refund().key());
            return new Observation(State.AVAILABLE, null);
        }

        @Override public Observation refundPartial(PartialCharge request) throws PaymentProviderReadFailure {
            sends++;
            var key = request.refund().key();
            var saved = refunds.computeIfAbsent(key, id ->
                    new Observation(reject ? State.REJECTED : State.CONFIRMED, "fixture-" + id));
            if (loseReply) {
                loseReply = false;
                throw new PaymentProviderReadFailure(0);
            }
            return saved;
        }
    }
}
