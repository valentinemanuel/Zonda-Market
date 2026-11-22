package com.zondamarket.integrations.payment;

import com.google.gson.JsonObject;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPHttpClient;
import com.mercadopago.net.MPResponse;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CheckoutProGateway.Checkout;
import com.zondamarket.checkout.service.CheckoutProGateway.Uncertain;
import com.zondamarket.checkout.service.CheckoutProGateway.Unavailable;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoCheckoutProGatewayTest {
    private static final Instant NOW = Instant.parse("2026-11-22T18:00:00Z");
    private static final String ORIGIN = "https://zonda.example.test";
    private static final String PREFERENCE = "800-00000000-0000-4000-8000-000000000000";
    private static final String LINK = "https://www.mercadopago.com/mla/checkout/start?pref_id=" + PREFERENCE;
    private final Checkout checkout = new Checkout(UUID.randomUUID(), UUID.randomUUID(), "zonda-fixture",
            new BigDecimal("24.50"), "ARS", NOW.plusSeconds(900));

    @Test
    void sendsFrozenPreferenceAndRecoversItByReferenceAfterATimeout() throws Exception {
        var payload = new JsonObject[] {null};
        var gateway = gateway(request -> {
            var uri = URI.create(request.getUri());
            return switch (uri.getPath()) {
                case "/users/me" -> response("{\"id\":800,\"site_id\":\"MLA\",\"tags\":[\"test_user\"]}");
                case "/checkout/preferences" -> {
                    assertEquals(HttpMethod.POST, request.getMethod());
                    assertEquals(List.of(checkout.idempotencyKey().toString()), request.getHeaders().entrySet().stream()
                            .filter(header -> header.getKey().equalsIgnoreCase("X-Idempotency-Key"))
                            .map(Map.Entry::getValue).toList());
                    payload[0] = request.getPayload();
                    throw new MPException("fixture timeout");
                }
                case "/checkout/preferences/search" -> {
                    assertTrue(uri.getQuery().contains("external_reference=zonda-fixture"));
                    yield response("{\"total\":1,\"elements\":[{\"id\":\"" + PREFERENCE + "\"}]}");
                }
                case "/checkout/preferences/" + PREFERENCE -> {
                    var preference = payload[0].deepCopy();
                    preference.addProperty("collector_id", 800);
                    preference.addProperty("id", PREFERENCE);
                    preference.addProperty("init_point", LINK);
                    yield response(preference.toString());
                }
                default -> throw new AssertionError(uri.getPath());
            };
        });
        assertThrows(Uncertain.class, () -> gateway.create(checkout));
        var body = payload[0];
        assertEquals("wallet_purchase", body.get("purpose").getAsString());
        assertEquals(checkout.expiresAt().toString(), body.get("expiration_date_to").getAsString());
        assertEquals(ORIGIN + "/checkout/resultado/" + checkout.orderId(),
                body.getAsJsonObject("back_urls").get("pending").getAsString());
        assertEquals(ORIGIN + "/api/v1/webhooks/mercadopago?source_news=webhooks", body.get("notification_url").getAsString());
        var item = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(checkout.amount(), item.get("unit_price").getAsBigDecimal());
        assertEquals("ARS", item.get("currency_id").getAsString());
        assertEquals(LINK, gateway.recover(checkout).orElseThrow().url());
    }

    @Test
    void rejectsUnsafeReturnAndCollectorBeforeCreatingAPreference() {
        assertThrows(IllegalStateException.class, () -> new MercadoPagoCheckoutProGateway("fixture", 800,
                ORIGIN + "/checkout", ORIGIN, Clock.fixed(NOW, ZoneOffset.UTC), request -> {
                    throw new AssertionError(request.getUri());
                }));
        var gateway = gateway(request -> {
            assertEquals("/users/me", URI.create(request.getUri()).getPath());
            return response("{\"id\":800,\"site_id\":\"MLA\",\"tags\":[]}");
        });
        assertThrows(Unavailable.class, () -> gateway.create(checkout));
    }

    @Test
    void verifiesBalancePaymentDetailsFetchedById() throws Exception {
        var payment = paymentGateway("account_money", "24.50", "ARS", 800).lookup(checkout).orElseThrow();
        assertEquals("123", payment.id());
        assertEquals(Status.APROBADO, payment.status());
    }

    @ParameterizedTest
    @CsvSource({"credit_card,24.50,ARS,800", "account_money,24.51,ARS,800",
            "account_money,24.50,USD,800", "account_money,24.50,ARS,801"})
    void rejectsDiscordantPaymentDetails(String method, String amount, String currency, int collector) throws Exception {
        assertTrue(paymentGateway(method, amount, currency, collector).lookup(checkout).isEmpty());
    }

    private MercadoPagoCheckoutProGateway paymentGateway(String method, String amount, String currency, int collector) {
        return gateway(request -> {
            var uri = URI.create(request.getUri());
            return switch (uri.getPath()) {
                case "/v1/payments/search" -> {
                    assertTrue(uri.getQuery().contains("external_reference=zonda-fixture"));
                    yield response("""
                            {"paging":{"total":1},"results":[{"id":123,"external_reference":"zonda-fixture","status":"approved"}]}
                            """);
                }
                case "/v1/payments/123" -> response("""
                        {"id":123,"external_reference":"zonda-fixture","status":"approved","status_detail":"accredited",
                         "payment_type_id":"%s","payment_method_id":"%s","transaction_amount":%s,"currency_id":"%s","collector_id":%d}
                        """.formatted(method, method, amount, currency, collector));
                default -> throw new AssertionError(uri.getPath());
            };
        });
    }

    private static MercadoPagoCheckoutProGateway gateway(MPHttpClient client) {
        return new MercadoPagoCheckoutProGateway("fixture", 800, ORIGIN, "http://localhost:3000," + ORIGIN,
                Clock.fixed(NOW, ZoneOffset.UTC), client);
    }

    private static MPResponse response(String content) {
        return new MPResponse(200, Map.of(), content);
    }
}
