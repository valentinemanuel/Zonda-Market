package com.zondamarket.integrations.payment;

import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPResponse;
import com.mercadopago.exceptions.MPApiException;
import com.zondamarket.checkout.service.PaymentRefundGateway.Charge;
import com.zondamarket.checkout.service.PaymentRefundGateway.PartialCharge;
import com.zondamarket.checkout.service.PaymentRefundGateway.State;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoRefundTest {
    private static final String ACCOUNT = """
            {"id":800,"site_id":"MLA","tags":["test_user"]}
            """;
    private static final String ORDER = "ORD01J49MMW3SSBK5PSV3DFR32959";
    private static final String CARD_PAYMENT = "PAY01J67CQQH5904WDBVZEM4JMEP3";
    private static final String CARD_REFUND = "REF01J49MMW3SSBK5PSV3DFR32959";
    private static final String REFERENCE = "zonda-fixture";
    private static final BigDecimal AMOUNT = new BigDecimal("24.50");
    private static final String ORIGIN = "https://zonda.example.test";

    @Test
    void refundsTheSingleCardOrderWithTheStableKeyAndRecognizesItsHistory() throws Exception {
        var charge = new Charge(UUID.randomUUID(), ORDER, CARD_PAYMENT, REFERENCE, AMOUNT, "ARS");
        var posted = new AtomicBoolean();
        var gateway = new MercadoPagoCardGateway("publica", "privada-ficticia", 800, Clock.systemUTC(), request -> {
            var path = URI.create(request.getUri()).getPath();
            if (path.equals("/users/me")) return response(ACCOUNT);
            if (path.equals("/v1/orders/" + ORDER)) {
                assertEquals(HttpMethod.GET, request.getMethod());
                return response(cardOrder(posted.get() ? "refunded" : "accredited", posted.get() ? cardRefund() : ""));
            }
            assertEquals("/v1/orders/" + ORDER + "/refund", path);
            assertEquals(HttpMethod.POST, request.getMethod());
            assertEquals(charge.key().toString(), idempotencyHeader(request.getHeaders()));
            posted.set(true);
            return response("""
                    {"id":"%s","transactions":{"refunds":[%s]}}
                    """.formatted(ORDER, cardRefund()));
        });
        assertEquals(State.AVAILABLE, gateway.inspect(charge).state());
        assertEquals(CARD_REFUND, gateway.refund(charge).refundId());
        assertEquals(State.CONFIRMED, gateway.inspect(charge).state());
    }

    @Test
    void aDifferentCardTransactionOrPartialRefundNeedsReview() throws Exception {
        var charge = new Charge(UUID.randomUUID(), ORDER, CARD_PAYMENT, REFERENCE, AMOUNT, "ARS");
        var gateway = new MercadoPagoCardGateway("publica", "privada-ficticia", 800, Clock.systemUTC(), request -> {
            assertEquals(HttpMethod.GET, request.getMethod());
            return response(cardOrder("partially_refunded", cardRefund().replace("24.50", "2.50")));
        });
        assertEquals(State.REVIEW, gateway.inspect(charge).state());
        var wrong = new Charge(charge.key(), ORDER, "PAY01J67CQQH5904WDBVZEM4JMEP4", REFERENCE, AMOUNT, "ARS");
        assertEquals(State.REVIEW, gateway.inspect(wrong).state());
    }

    @Test
    void waitsForTheHistoryWhenTheOrderIsAlreadyMarkedAsRefunded() throws Exception {
        var charge = new Charge(UUID.randomUUID(), ORDER, CARD_PAYMENT, REFERENCE, AMOUNT, "ARS");
        var gateway = new MercadoPagoCardGateway("publica", "privada-ficticia", 800, Clock.systemUTC(), request -> {
            assertEquals(HttpMethod.GET, request.getMethod());
            return response(cardOrder("refunded", ""));
        });
        assertEquals(State.PENDING, gateway.inspect(charge).state());
    }

    @Test
    void checkoutProRefundUsesPaymentsApiAndReadsTheRefundList() throws Exception {
        var charge = new Charge(UUID.randomUUID(), "123", "123", REFERENCE, AMOUNT, "ARS");
        var posted = new AtomicBoolean();
        String refund = """
                {"id":654,"payment_id":123,"amount":24.50,"status":"approved"}
                """;
        var gateway = new MercadoPagoCheckoutProGateway("privada-ficticia", 800, ORIGIN, ORIGIN, Clock.systemUTC(), request -> {
            var path = URI.create(request.getUri()).getPath();
            if (path.equals("/users/me")) return response(ACCOUNT);
            if (path.equals("/v1/payments/123")) {
                return response("""
                        {"id":123,"collector_id":800,"external_reference":"zonda-fixture","currency_id":"ARS",
                         "transaction_amount":24.50,"payment_method_id":"account_money","payment_type_id":"account_money",
                         "status":"approved","status_detail":"accredited"}
                        """);
            }
            assertEquals("/v1/payments/123/refunds", path);
            if (request.getMethod() == HttpMethod.GET) return response(posted.get() ? "[" + refund + "]" : "[]");
            assertEquals(HttpMethod.POST, request.getMethod());
            assertEquals(charge.key().toString(), idempotencyHeader(request.getHeaders()));
            assertFalse(request.getPayload().has("amount"));
            posted.set(true);
            return response(refund);
        });
        assertEquals(State.AVAILABLE, gateway.inspect(charge).state());
        assertEquals(State.CONFIRMED, gateway.refund(charge).state());
        assertEquals("654", gateway.inspect(charge).refundId());
    }

    @Test
    void cardUsesTheOriginalTransactionAndKeepsUnknownRefundsUnboundUntilTheReply() throws Exception {
        var charge = new Charge(UUID.randomUUID(), ORDER, CARD_PAYMENT, REFERENCE, new BigDecimal("5.25"), "ARS");
        var prior = cardRefund().replace(CARD_REFUND, "refund-prior").replace("24.50", "4.00");
        var current = cardRefund().replace("24.50", "5.25");
        var posted = new AtomicBoolean();
        var gateway = new MercadoPagoCardGateway("publica", "privada-ficticia", 800, Clock.systemUTC(), request -> {
            var path = URI.create(request.getUri()).getPath();
            if (path.equals("/users/me")) return response(ACCOUNT);
            String rows = prior + (posted.get() ? "," + current : "");
            if (request.getMethod() == HttpMethod.GET) {
                return response(cardOrder("partially_refunded", rows));
            }
            assertEquals("/v1/orders/" + ORDER + "/refund", path);
            assertEquals(charge.key().toString(), idempotencyHeader(request.getHeaders()));
            var line = request.getPayload().getAsJsonArray("transactions").get(0).getAsJsonObject();
            assertEquals(CARD_PAYMENT, line.get("id").getAsString());
            assertEquals("5.25", line.get("amount").getAsString());
            posted.set(true);
            return response("""
                    {"id":"%s","transactions":{"refunds":[%s,%s]}}
                    """.formatted(ORDER, prior, current));
        });
        var first = new PartialCharge(charge, AMOUNT, null, List.of("refund-prior"), false);
        assertEquals(State.AVAILABLE, gateway.inspectPartial(first).state());
        assertEquals(CARD_REFUND, gateway.refundPartial(first).refundId());
        assertEquals(State.REVIEW, gateway.inspectPartial(first).state());
        var lostReply = new PartialCharge(charge, AMOUNT, null, List.of("refund-prior"), true);
        assertEquals(State.AVAILABLE, gateway.inspectPartial(lostReply).state());
        assertEquals(CARD_REFUND, gateway.refundPartial(lostReply).refundId());
        var bound = new PartialCharge(charge, AMOUNT, CARD_REFUND, List.of("refund-prior"), true);
        assertEquals(State.CONFIRMED, gateway.inspectPartial(bound).state());
    }

    @Test
    void checkoutProSendsAnExactPartialAmountAndReadsDefinitiveRejection() throws Exception {
        var charge = new Charge(UUID.randomUUID(), "123", "123", REFERENCE, new BigDecimal("2.15"), "ARS");
        String rejected = """
                {"id":654,"payment_id":123,"amount":2.15,"status":"rejected"}
                """;
        var gateway = new MercadoPagoCheckoutProGateway("privada-ficticia", 800, ORIGIN, ORIGIN, Clock.systemUTC(), request -> {
            var path = URI.create(request.getUri()).getPath();
            if (path.equals("/users/me")) return response(ACCOUNT);
            if (path.equals("/v1/payments/123")) return response("""
                    {"id":123,"collector_id":800,"external_reference":"zonda-fixture","currency_id":"ARS",
                     "transaction_amount":24.50,"payment_method_id":"account_money","payment_type_id":"account_money",
                     "status":"approved","status_detail":"accredited"}
                    """);
            assertEquals("/v1/payments/123/refunds", path);
            if (request.getMethod() == HttpMethod.GET) return response("[" + rejected + "]");
            assertEquals(charge.key().toString(), idempotencyHeader(request.getHeaders()));
            assertEquals(new BigDecimal("2.15"), request.getPayload().get("amount").getAsBigDecimal());
            return response(rejected);
        });
        var read = new PartialCharge(charge, AMOUNT, "654", List.of(), true);
        assertEquals(State.REJECTED, gateway.inspectPartial(read).state());
        var send = new PartialCharge(charge, AMOUNT, null, List.of(), false);
        assertEquals(State.REJECTED, gateway.refundPartial(send).state());
    }

    @Test
    void onlyTheFirstWriteCanUseAnExplicitAmountRejectionToReleaseAReserve() {
        var charge = new Charge(UUID.randomUUID(), ORDER, CARD_PAYMENT, REFERENCE, AMOUNT, "ARS");
        var error = new MPApiException("fixture", new MPResponse(400, Map.of(),
                "{\"errors\":[{\"code\":\"refund_amount_exceeds\"}]}"));
        assertTrue(MercadoPagoRefunds.definitiveWriteRejection(error,
                new PartialCharge(charge, AMOUNT, null, List.of(), false)));
        assertFalse(MercadoPagoRefunds.definitiveWriteRejection(error,
                new PartialCharge(charge, AMOUNT, null, List.of(), true)));
    }

    private static String cardOrder(String detail, String refunds) {
        return """
                {"id":"%s","type":"online","country_code":"ARG","user_id":"800","external_reference":"%s",
                 "total_amount":"24.50","status":"processed","status_detail":"%s",
                 "transactions":{"payments":[{"id":"%s","amount":"24.50","status":"processed","status_detail":"%s",
                   "payment_method":{"type":"credit_card"}}],"refunds":[%s]}}
                """.formatted(ORDER, REFERENCE, detail, CARD_PAYMENT, detail, refunds);
    }

    private static String cardRefund() {
        return """
                {"id":"%s","transaction_id":"%s","amount":"24.50","status":"processed"}
                """.formatted(CARD_REFUND, CARD_PAYMENT);
    }

    private static MPResponse response(String content) {
        return new MPResponse(200, Map.of(), content);
    }

    private static String idempotencyHeader(Map<String, String> headers) {
        return headers.entrySet().stream().filter(header -> header.getKey().equalsIgnoreCase("X-Idempotency-Key"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }
}
