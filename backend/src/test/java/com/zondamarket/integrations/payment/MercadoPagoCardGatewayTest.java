package com.zondamarket.integrations.payment;

import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPHttpClient;
import com.mercadopago.net.MPRequest;
import com.mercadopago.net.MPResponse;
import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CardPaymentGateway.Charge;
import com.zondamarket.checkout.service.CardPaymentGateway.NoCharge;
import com.zondamarket.checkout.service.CardPaymentGateway.SubmissionUncertain;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoCardGatewayTest {
    private static final Instant NOW = Instant.parse("2026-11-21T18:00:00Z");
    private static final String TEST_ACCOUNT = """
            {"id":800,"site_id":"MLA","tags":["test_user"]}
            """;
    private final Charge charge = new Charge(UUID.randomUUID(), "zonda-fixture", new BigDecimal("24.50"), NOW.plusSeconds(900));

    @ParameterizedTest
    @CsvSource({"credit_card,master,approved,APROBADO", "debit_card,debvisa,rejected,RECHAZADO"})
    void sendsOneCreateAndUsesGetToVerifyTheResult(String type, String method, String providerStatus, Status expected) throws Exception {
        List<MPRequest> requests = new ArrayList<>();
        var gateway = gateway(request -> {
            requests.add(request);
            return switch (path(request)) {
                case "/users/me" -> response(TEST_ACCOUNT);
                case "/v1/payments" -> response("{\"id\":721,\"status\":\"approved\"}");
                case "/v1/payments/721" -> response(payment(type, providerStatus, 800, false));
                default -> throw new AssertionError(request.getUri());
            };
        });
        var card = new CardPaymentRequest(UUID.randomUUID(), "token_ficticio_tarjeta", method, "33", 1,
                new CardPaymentRequest.Payer("pagador@example.test", new CardPaymentRequest.Identification("DNI", "12345678")));
        long id = gateway.create(charge, card);
        var verified = gateway.lookup(id, charge.externalReference()).orElseThrow();
        assertEquals(expected, verified.status());
        assertEquals(List.of("/users/me", "/v1/payments", "/v1/payments/721"),
                requests.stream().map(MercadoPagoCardGatewayTest::path).toList());
        var create = requests.get(1);
        assertEquals(List.of(charge.idempotencyKey().toString()), create.getHeaders().entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase("X-Idempotency-Key")).map(Map.Entry::getValue).toList());
        assertEquals("Bearer privada-ficticia", create.getHeaders().get("Authorization"));
        assertEquals(charge.amount(), create.getPayload().get("transaction_amount").getAsBigDecimal());
        assertEquals(charge.externalReference(), create.getPayload().get("external_reference").getAsString());
        assertEquals(method, create.getPayload().get("payment_method_id").getAsString());
        assertEquals("token_ficticio_tarjeta", create.getPayload().get("token").getAsString());
        assertFalse(card.toString().contains(card.token()));
    }

    @Test
    void searchesBeforeRecoveringFromATimeoutAndRejectsUnverifiedPayments() throws Exception {
        List<String> paths = new ArrayList<>();
        var gateway = gateway(request -> {
            paths.add(path(request));
            return switch (path(request)) {
                case "/users/me" -> response(TEST_ACCOUNT);
                case "/v1/payments" -> throw new MPException("timeout ficticio");
                case "/v1/payments/search" -> {
                    assertTrue(URI.create(request.getUri()).getQuery().contains("external_reference=" + charge.externalReference()));
                    yield response("""
                            {"paging":{"total":1,"limit":2,"offset":0},"results":[{"id":721,"external_reference":"zonda-fixture"}]}
                            """);
                }
                case "/v1/payments/721" -> response(payment("credit_card", "approved", 999, false));
                default -> throw new AssertionError(request.getUri());
            };
        });
        assertThrows(SubmissionUncertain.class, () -> gateway.create(charge, card()));
        assertTrue(gateway.lookup(null, charge.externalReference()).isEmpty());
        assertEquals(List.of("/users/me", "/v1/payments", "/v1/payments/search", "/v1/payments/721"), paths);
        var live = gateway(request -> response(payment("credit_card", "approved", 800, true)));
        assertTrue(live.lookup(721L, charge.externalReference()).isEmpty());
    }

    @Test
    void refusesToCallThePaymentEndpointForANonTestCollector() {
        List<String> paths = new ArrayList<>();
        var gateway = gateway(request -> {
            paths.add(path(request));
            return response("{\"id\":800,\"site_id\":\"MLA\",\"tags\":[\"normal\"]}");
        });
        assertThrows(NoCharge.class, () -> gateway.create(charge, card()));
        assertEquals(List.of("/users/me"), paths);
    }

    private static CardPaymentRequest card() {
        return new CardPaymentRequest(UUID.randomUUID(), "token_ficticio_tarjeta", "visa", null, 1,
                new CardPaymentRequest.Payer("pagador@example.test", null));
    }

    private static MercadoPagoCardGateway gateway(MPHttpClient http) {
        return new MercadoPagoCardGateway("publica-ficticia", "privada-ficticia", 800, Clock.fixed(NOW, ZoneOffset.UTC), http);
    }

    private static MPResponse response(String body) {
        return new MPResponse(200, Map.of(), body);
    }

    private static String path(MPRequest request) {
        var uri = URI.create(request.getUri());
        assertEquals("https", uri.getScheme());
        assertEquals("api.mercadopago.com", uri.getHost());
        return uri.getPath();
    }

    private static String payment(String type, String status, long collector, boolean live) {
        return """
                {"id":721,"payment_type_id":"%s","status":"%s","collector_id":%d,"live_mode":%b,
                  "external_reference":"zonda-fixture","transaction_amount":24.50,"currency_id":"ARS"}
                """.formatted(type, status, collector, live);
    }
}
