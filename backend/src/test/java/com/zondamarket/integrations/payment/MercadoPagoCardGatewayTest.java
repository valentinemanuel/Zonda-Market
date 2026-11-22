package com.zondamarket.integrations.payment;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.HttpMethod;
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
    private static final String ID = "ORD01J49MMW3SSBK5PSV3DFR32959";
    private static final String TEST_ACCOUNT = """
            {"id":800,"site_id":"MLA","tags":["test_user"]}
            """;
    private final Charge charge = new Charge(UUID.randomUUID(), "zonda-fixture", new BigDecimal("24.50"),
            NOW.plusSeconds(900), "AR-B");

    @ParameterizedTest
    @CsvSource({"credit_card,master,processed,accredited,APROBADO", "debit_card,debvisa,failed,failed,RECHAZADO"})
    void createsAnOrderWithOneStableKeyAndVerifiesItViaGet(String type, String method, String state,
                                                            String detail, Status expected) throws Exception {
        List<MPRequest> requests = new ArrayList<>();
        var gateway = gateway(request -> {
            requests.add(request);
            return switch (path(request)) {
                case "/users/me" -> response(TEST_ACCOUNT);
                case "/v1/orders" -> {
                    if (expected == Status.RECHAZADO) {
                        throw new MPApiException("rechazo ficticio",
                                new MPResponse(402, Map.of(), "{\"data\":{\"id\":\"" + ID + "\"}}"));
                    }
                    yield response("{\"id\":\"" + ID + "\"}");
                }
                case "/v1/orders/" + ID -> response(order(type, state, detail, 800, false));
                default -> throw new AssertionError(request.getUri());
            };
        });
        var card = card(method, type);
        assertEquals(ID, gateway.create(charge, card));
        assertEquals(expected, gateway.lookup(ID, charge.externalReference(), charge.expiresAt()).orElseThrow().status());
        assertEquals(List.of("/users/me", "/v1/orders", "/v1/orders/" + ID),
                requests.stream().map(MercadoPagoCardGatewayTest::path).toList());
        var create = requests.get(1);
        assertEquals(List.of(charge.idempotencyKey().toString()), create.getHeaders().entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase("X-Idempotency-Key")).map(Map.Entry::getValue).toList());
        assertEquals("Bearer privada-ficticia", create.getHeaders().get("Authorization"));
        var body = create.getPayload();
        assertEquals("24.50", body.get("total_amount").getAsString());
        assertTrue(body.get("total_amount").getAsJsonPrimitive().isString());
        assertEquals(charge.externalReference(), body.get("external_reference").getAsString());
        assertEquals("AR-B", body.getAsJsonObject("config").getAsJsonObject("location").get("state_id").getAsString());
        var payment = body.getAsJsonObject("transactions").getAsJsonArray("payments").get(0).getAsJsonObject();
        assertEquals("24.50", payment.get("amount").getAsString());
        var sent = payment.getAsJsonObject("payment_method");
        assertEquals(method, sent.get("id").getAsString());
        assertEquals(type, sent.get("type").getAsString());
        assertEquals("token_ficticio_tarjeta", sent.get("token").getAsString());
        assertFalse(card.toString().contains(card.token()));
    }

    @Test
    void searchesByReferenceWithoutRepeatingTheChargeAfterTimeout() throws Exception {
        List<String> paths = new ArrayList<>();
        var gateway = gateway(request -> {
            paths.add(path(request));
            return switch (path(request)) {
                case "/users/me" -> response(TEST_ACCOUNT);
                case "/v1/orders" -> {
                    if (request.getMethod() == HttpMethod.POST) throw new MPException("timeout ficticio");
                    var query = URI.create(request.getUri()).getQuery();
                    assertTrue(query.contains("external_reference=" + charge.externalReference()));
                    assertTrue(query.contains("begin_date=") && query.contains("end_date="));
                    yield response("""
                            {"data":[{"id":"%s","external_reference":"zonda-fixture"}]}
                            """.formatted(ID));
                }
                case "/v1/orders/" + ID -> response(order("credit_card", "processed", "accredited", 800, false));
                default -> throw new AssertionError(request.getUri());
            };
        });
        assertThrows(SubmissionUncertain.class, () -> gateway.create(charge, card("visa", "credit_card")));
        assertEquals(Status.APROBADO, gateway.lookup(null, charge.externalReference(), charge.expiresAt()).orElseThrow().status());
        assertEquals(List.of("/users/me", "/v1/orders", "/v1/orders", "/v1/orders/" + ID), paths);
    }

    @Test
    void refusesNonTestOrMismatchedCollector() throws Exception {
        List<String> paths = new ArrayList<>();
        var gateway = gateway(request -> {
            paths.add(path(request));
            return response("{\"id\":800,\"site_id\":\"MLA\",\"tags\":[\"normal\"]}");
        });
        assertThrows(NoCharge.class, () -> gateway.create(charge, card("visa", "credit_card")));
        assertEquals(List.of("/users/me"), paths);
        var other = gateway(request -> response(order("debit_card", "processed", "accredited", 801, false)));
        assertTrue(other.lookup(ID, charge.externalReference(), charge.expiresAt()).isEmpty());
        var live = gateway(request -> response(order("debit_card", "processed", "accredited", 800, true)));
        assertTrue(live.lookup(ID, charge.externalReference(), charge.expiresAt()).isEmpty());
    }

    private static CardPaymentRequest card(String method, String type) {
        return new CardPaymentRequest(UUID.randomUUID(), "token_ficticio_tarjeta", method, type, 1,
                new CardPaymentRequest.Payer("pagador@example.test", new CardPaymentRequest.Identification("DNI", "12345678")));
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

    private static String order(String type, String state, String detail, long collector, boolean live) {
        return """
                {"id":"%s","type":"online","country_code":"ARG","user_id":"%d","live_mode":%b,
                 "external_reference":"zonda-fixture","total_amount":"24.50","status":"%s","status_detail":"%s",
                 "transactions":{"payments":[{"id":"PAY01J67CQQH5904WDBVZEM4JMEP3","amount":"24.50",
                   "status":"%s","status_detail":"%s","payment_method":{"type":"%s"}}]}}
                """.formatted(ID, collector, live, state, detail, state, detail, type);
    }
}
