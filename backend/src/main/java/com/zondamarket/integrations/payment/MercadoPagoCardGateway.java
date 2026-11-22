package com.zondamarket.integrations.payment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.MercadoPagoClient;
import com.mercadopago.client.user.UserClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPDefaultHttpClient;
import com.mercadopago.net.MPHttpClient;
import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CardPaymentGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("(local | test | demo) & !prod & !production")
@ConditionalOnProperty(name = "zonda.mercado-pago.test-enabled", havingValue = "true")
class MercadoPagoCardGateway implements CardPaymentGateway {
    private static final String ORDERS = "/v1/orders";
    private static final Set<String> CARD_TYPES = Set.of("credit_card", "debit_card");
    private static final Set<String> TERMINAL_FAILURES = Set.of("failed", "canceled", "expired");
    private static final Set<String> OPEN_STATES = Set.of("created", "processing", "action_required", "in_review");
    private final String publicKey;
    private final String accessToken;
    private final long collector;
    private final CardOrdersClient orders;
    private final UserClient users;
    private final Clock clock;

    @Autowired
    MercadoPagoCardGateway(@Value("${zonda.mercado-pago.public-key}") String publicKey,
                           @Value("${zonda.mercado-pago.access-token}") String accessToken,
                           @Value("${zonda.mercado-pago.test-collector-id}") long collector, Clock clock) {
        this(publicKey, accessToken, collector, clock, transport());
    }

    MercadoPagoCardGateway(String publicKey, String accessToken, long collector, Clock clock, MPHttpClient http) {
        if (publicKey.isBlank() || accessToken.isBlank() || collector <= 0) {
            throw new IllegalStateException("Configurá las credenciales y el colector de prueba de Mercado Pago.");
        }
        this.publicKey = publicKey;
        this.accessToken = accessToken;
        this.collector = collector;
        this.clock = clock;
        this.orders = new CardOrdersClient(http);
        this.users = new UserClient(http);
    }

    private static MPHttpClient transport() {
        // The SDK logs request and response bodies at debug level.
        MercadoPagoConfig.setLoggingLevel(Level.OFF);
        return new MPDefaultHttpClient();
    }

    @Override public String publicKey() { return publicKey; }

    @Override
    public String create(Charge charge, CardPaymentRequest card) throws SubmissionUncertain, NoCharge {
        verifyTestCollector();
        if (!clock.instant().isBefore(charge.expiresAt())) throw new NoCharge();
        try {
            var result = orders.exchange(ORDERS, HttpMethod.POST, payload(charge, card), null,
                    options(Map.of("X-Idempotency-Key", charge.idempotencyKey().toString())));
            return orderId(result).orElseThrow(SubmissionUncertain::new);
        } catch (MPApiException error) {
            if (error.getStatusCode() == 402 && error.getApiResponse() != null) {
                try {
                    var failure = parse(error.getApiResponse().getContent());
                    var id = orderId(failure).or(() -> orderId(object(failure, "data")));
                    if (id.isPresent()) return id.get();
                } catch (RuntimeException malformedResponse) {
                    throw new SubmissionUncertain();
                }
            }
            throw new SubmissionUncertain();
        } catch (MPException | RuntimeException error) {
            throw new SubmissionUncertain();
        }
    }

    @Override
    public Optional<Observation> lookup(String id, String reference, Instant expiresAt) throws SubmissionUncertain {
        try {
            var read = options(Map.of());
            if (id == null) {
                var begin = expiresAt.minus(1, ChronoUnit.DAYS);
                var end = expiresAt.plus(1, ChronoUnit.DAYS);
                var now = clock.instant();
                if (now.isBefore(end)) end = now;
                if (!begin.isBefore(end)) return Optional.empty();
                var found = orders.exchange(ORDERS, HttpMethod.GET, null, Map.of(
                        "external_reference", reference, "type", "online", "page", 1, "page_size", 2,
                        "begin_date", begin.truncatedTo(ChronoUnit.MILLIS).toString(),
                        "end_date", end.truncatedTo(ChronoUnit.MILLIS).toString()), read).getAsJsonArray("data");
                if (found == null || found.size() != 1) return Optional.empty();
                var row = found.get(0).getAsJsonObject();
                if (!reference.equals(value(row, "external_reference"))) return Optional.empty();
                id = orderId(row).orElse(null);
            }
            if (!validId(id)) return Optional.empty();
            return observation(orders.exchange(ORDERS + "/" + id, HttpMethod.GET, null, null, read), id, reference);
        } catch (MPException | MPApiException | RuntimeException error) {
            throw new SubmissionUncertain();
        }
    }

    private Optional<Observation> observation(JsonObject order, String id, String reference) {
        if (!id.equals(value(order, "id")) || !reference.equals(value(order, "external_reference"))
                || !"online".equals(value(order, "type")) || !"ARG".equals(value(order, "country_code"))
                || !Long.toString(collector).equals(value(order, "user_id"))
                || (order.has("currency") && !"ARS".equals(value(order, "currency")))
                || (order.has("live_mode") && !"false".equals(value(order, "live_mode")))) {
            return Optional.empty();
        }
        var payments = object(order, "transactions").getAsJsonArray("payments");
        if (payments == null || payments.size() != 1) return Optional.empty();
        var payment = payments.get(0).getAsJsonObject();
        var amount = new BigDecimal(value(order, "total_amount"));
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal(value(payment, "amount"))) != 0
                || !CARD_TYPES.contains(value(object(payment, "payment_method"), "type"))) {
            return Optional.empty();
        }
        String orderState = value(order, "status");
        String paymentState = value(payment, "status");
        if ("processed".equals(orderState) && "accredited".equals(value(order, "status_detail"))
                && "processed".equals(paymentState) && "accredited".equals(value(payment, "status_detail"))) {
            return Optional.of(new Observation(id, reference, amount, "ARS", Status.APROBADO));
        }
        if (TERMINAL_FAILURES.contains(orderState) && TERMINAL_FAILURES.contains(paymentState)) {
            return Optional.of(new Observation(id, reference, amount, "ARS", Status.RECHAZADO));
        }
        if (OPEN_STATES.contains(orderState)) return Optional.of(new Observation(id, reference, amount, "ARS", Status.PENDIENTE));
        return Optional.empty();
    }

    private void verifyTestCollector() throws NoCharge {
        try {
            var account = users.get(options(Map.of()));
            var tags = parse(account.getResponse().getContent()).getAsJsonArray("tags");
            if (!Objects.equals(collector, account.getId()) || !"MLA".equals(account.getSiteId()) || tags == null
                    || tags.asList().stream().noneMatch(tag -> tag.isJsonPrimitive() && "test_user".equals(tag.getAsString()))) {
                throw new NoCharge();
            }
        } catch (MPException | MPApiException | RuntimeException error) {
            throw new NoCharge();
        }
    }

    private static JsonObject payload(Charge charge, CardPaymentRequest card) {
        var method = new JsonObject();
        method.addProperty("id", card.paymentMethodId());
        method.addProperty("type", card.paymentType());
        method.addProperty("token", card.token());
        method.addProperty("installments", card.installments());

        var payment = new JsonObject();
        payment.addProperty("amount", charge.amount().toPlainString());
        payment.add("payment_method", method);
        var rows = new JsonArray();
        rows.add(payment);
        var transactions = new JsonObject();
        transactions.add("payments", rows);

        var payer = new JsonObject();
        payer.addProperty("email", card.payer().email());
        if (card.payer().identification() != null) {
            var identification = new JsonObject();
            identification.addProperty("type", card.payer().identification().type());
            identification.addProperty("number", card.payer().identification().number());
            payer.add("identification", identification);
        }

        var location = new JsonObject();
        location.addProperty("state_id", charge.provinceCode());
        var config = new JsonObject();
        config.add("location", location);

        var body = new JsonObject();
        body.addProperty("type", "online");
        body.addProperty("processing_mode", "automatic");
        body.addProperty("external_reference", charge.externalReference());
        body.addProperty("total_amount", charge.amount().toPlainString());
        body.add("payer", payer);
        body.add("config", config);
        body.add("transactions", transactions);
        return body;
    }

    private static JsonObject parse(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private static JsonObject object(JsonObject object, String name) {
        var child = object.get(name);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : new JsonObject();
    }

    private static String value(JsonObject object, String name) {
        var field = object.get(name);
        return field != null && field.isJsonPrimitive() ? field.getAsString() : "";
    }

    private static Optional<String> orderId(JsonObject order) {
        return Optional.of(value(order, "id")).filter(MercadoPagoCardGateway::validId);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{16,80}");
    }

    private MPRequestOptions options(Map<String, String> headers) {
        return MPRequestOptions.builder().accessToken(accessToken).customHeaders(headers)
                .connectionTimeout(1_500).connectionRequestTimeout(1_000).socketTimeout(3_500)
                .maxRetries(0).build();
    }

    private static final class CardOrdersClient extends MercadoPagoClient {
        CardOrdersClient(MPHttpClient http) { super(http); }

        JsonObject exchange(String path, HttpMethod method, JsonObject payload, Map<String, Object> query,
                            MPRequestOptions options) throws MPException, MPApiException {
            // Passing the options through this overload preserves exactly one idempotency header.
            return parse(send(path, method, payload, query, options).getContent());
        }
    }
}
