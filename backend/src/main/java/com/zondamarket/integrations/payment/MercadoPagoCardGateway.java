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
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CardPaymentGateway;
import com.zondamarket.checkout.service.PaymentProviderReadFailure;
import com.zondamarket.checkout.service.PaymentRefundGateway;
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
class MercadoPagoCardGateway implements CardPaymentGateway, PaymentRefundGateway {
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
    public String create(CardPaymentGateway.Charge charge, CardPaymentRequest card) throws SubmissionUncertain, NoCharge {
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
    public Optional<CardPaymentGateway.Observation> lookup(String id, String reference, Instant expiresAt) throws SubmissionUncertain {
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
            return lookupOrder(id).filter(observed -> reference.equals(observed.externalReference()));
        } catch (MPException | MPApiException | PaymentProviderReadFailure | RuntimeException error) {
            throw new SubmissionUncertain();
        }
    }

    @Override
    public Optional<CardPaymentGateway.Observation> lookupOrder(String id) throws PaymentProviderReadFailure {
        if (!validId(id)) return Optional.empty();
        try {
            return observation(orders.exchange(ORDERS + "/" + id, HttpMethod.GET, null, null, options(Map.of())), id);
        } catch (MPApiException error) {
            throw new PaymentProviderReadFailure(error.getStatusCode());
        } catch (MPException | RuntimeException error) {
            throw new PaymentProviderReadFailure(0);
        }
    }

    private Optional<CardPaymentGateway.Observation> observation(JsonObject order, String id) {
        var reference = value(order, "external_reference");
        if (reference.isBlank() || !matchesOrder(order, id)) {
            return Optional.empty();
        }
        var payments = object(order, "transactions").getAsJsonArray("payments");
        if (payments == null || payments.size() != 1) return Optional.empty();
        var payment = payments.get(0).getAsJsonObject();
        var paymentId = value(payment, "id");
        var amount = new BigDecimal(value(order, "total_amount"));
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal(value(payment, "amount"))) != 0
                || !CARD_TYPES.contains(value(object(payment, "payment_method"), "type"))) {
            return Optional.empty();
        }
        String orderState = value(order, "status");
        String paymentState = value(payment, "status");
        if ("processed".equals(orderState) && "accredited".equals(value(order, "status_detail"))
                && "processed".equals(paymentState) && "accredited".equals(value(payment, "status_detail"))) {
            if (!validId(paymentId)) return Optional.empty();
            return Optional.of(new CardPaymentGateway.Observation(id, paymentId, reference, amount, "ARS", Status.APROBADO));
        }
        if (!validId(paymentId)) paymentId = null;
        if (TERMINAL_FAILURES.contains(orderState) && TERMINAL_FAILURES.contains(paymentState)) {
            return Optional.of(new CardPaymentGateway.Observation(id, paymentId, reference, amount, "ARS", Status.RECHAZADO));
        }
        if (OPEN_STATES.contains(orderState)) {
            return Optional.of(new CardPaymentGateway.Observation(id, paymentId, reference, amount, "ARS", Status.PENDIENTE));
        }
        return Optional.empty();
    }

    private boolean matchesOrder(JsonObject order, String id) {
        return id.equals(value(order, "id")) && "online".equals(value(order, "type"))
                && "ARG".equals(value(order, "country_code")) && Long.toString(collector).equals(value(order, "user_id"))
                && (!order.has("currency") || "ARS".equals(value(order, "currency")))
                && (!order.has("live_mode") || "false".equals(value(order, "live_mode")));
    }

    @Override public Method refundMethod() { return Method.CARD; }

    @Override
    public PaymentRefundGateway.Observation inspect(PaymentRefundGateway.Charge charge) throws PaymentProviderReadFailure {
        if (!validId(charge.orderId()) || !validId(charge.paymentId()) || !"ARS".equals(charge.currency())) {
            return MercadoPagoRefunds.REVIEW;
        }
        try {
            var order = orders.exchange(ORDERS + "/" + charge.orderId(), HttpMethod.GET, null, null, options(Map.of()));
            var transactions = object(order, "transactions");
            var payments = transactions.getAsJsonArray("payments");
            if (!matchesOrder(order, charge.orderId()) || !charge.reference().equals(value(order, "external_reference"))
                    || !sameAmount(order, "total_amount", charge.amount()) || payments == null || payments.size() != 1) {
                return MercadoPagoRefunds.REVIEW;
            }
            var payment = payments.get(0).getAsJsonObject();
            if (!charge.paymentId().equals(value(payment, "id")) || !sameAmount(payment, "amount", charge.amount())
                    || !CARD_TYPES.contains(value(object(payment, "payment_method"), "type"))) return MercadoPagoRefunds.REVIEW;
            var refunds = transactions.get("refunds");
            if (refunds != null && !refunds.isJsonNull() && !refunds.isJsonArray()) return MercadoPagoRefunds.REVIEW;
            return MercadoPagoRefunds.read(refunds == null || refunds.isJsonNull() ? null : refunds.getAsJsonArray(),
                    charge, "transaction_id", "processed",
                    accredited(order) && accredited(payment));
        } catch (MPApiException error) {
            throw new PaymentProviderReadFailure(error.getStatusCode());
        } catch (MPException | RuntimeException error) {
            throw new PaymentProviderReadFailure(0);
        }
    }

    @Override
    public PaymentRefundGateway.Observation refund(PaymentRefundGateway.Charge charge) throws PaymentProviderReadFailure {
        if (!validId(charge.orderId()) || !validId(charge.paymentId())) return MercadoPagoRefunds.REVIEW;
        try {
            verifyTestCollector();
            var reply = orders.exchange(ORDERS + "/" + charge.orderId() + "/refund", HttpMethod.POST, null, null,
                    options(Map.of("X-Idempotency-Key", charge.key().toString())));
            var rows = object(reply, "transactions").getAsJsonArray("refunds");
            if (!charge.orderId().equals(value(reply, "id")) || rows == null || rows.isEmpty()) {
                throw new PaymentProviderReadFailure(0);
            }
            return MercadoPagoRefunds.read(rows, charge, "transaction_id", "processed", false);
        } catch (NoCharge error) {
            throw new PaymentProviderReadFailure(0);
        } catch (MPApiException error) {
            throw new PaymentProviderReadFailure(error.getStatusCode());
        } catch (MPException | RuntimeException error) {
            throw new PaymentProviderReadFailure(0);
        }
    }

    private static boolean sameAmount(JsonObject value, String field, BigDecimal expected) {
        return expected.compareTo(new BigDecimal(value(value, field))) == 0;
    }

    private static boolean accredited(JsonObject value) {
        return "processed".equals(value(value, "status")) && "accredited".equals(value(value, "status_detail"));
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

    private static JsonObject payload(CardPaymentGateway.Charge charge, CardPaymentRequest card) {
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
