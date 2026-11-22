package com.zondamarket.integrations.payment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.MercadoPagoClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPDefaultHttpClient;
import com.mercadopago.net.MPHttpClient;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.service.CheckoutProGateway;
import com.zondamarket.checkout.service.PaymentProviderReadFailure;
import com.zondamarket.checkout.service.PaymentRefundGateway;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
@ConditionalOnProperty(name = "zonda.mercado-pago.pro.test-enabled", havingValue = "true")
class MercadoPagoCheckoutProGateway implements CheckoutProGateway, PaymentRefundGateway {
    private static final String PREFERENCES = "/checkout/preferences";
    private static final String PAYMENTS = "/v1/payments";
    private static final Set<String> CHECKOUT_HOSTS = Set.of("www.mercadopago.com.ar", "www.mercadopago.com");
    private static final Set<String> CHECKOUT_PATHS = Set.of("/checkout/v1/redirect",
            "/checkout/v1/payment/redirect", "/mla/checkout/start");
    private static final List<String> EXCLUDED_TYPES = List.of("credit_card", "debit_card", "prepaid_card", "ticket",
            "atm", "bank_transfer", "digital_currency", "digital_wallet", "consumer_credits", "voucher", "crypto");
    private static final Set<String> PENDING = Set.of("pending", "in_process", "authorized", "in_mediation");
    private final String token;
    private final String collector;
    private final String publicOrigin;
    private final Clock clock;
    private final Client client;

    @Autowired
    MercadoPagoCheckoutProGateway(@Value("${zonda.mercado-pago.pro.access-token}") String token,
                                  @Value("${zonda.mercado-pago.pro.test-collector-id}") long collector,
                                  @Value("${zonda.mercado-pago.pro.public-origin}") String publicOrigin,
                                  @Value("${zonda.auth.allowed-origins}") String allowedOrigins, Clock clock) {
        this(token, collector, publicOrigin, allowedOrigins, clock, transport());
    }

    MercadoPagoCheckoutProGateway(String token, long collector, String publicOrigin, String allowedOrigins,
                                  Clock clock, MPHttpClient transport) {
        if (token.isBlank() || collector <= 0) {
            throw new IllegalStateException("Configurá el token y el colector de prueba de Checkout Pro.");
        }
        var origin = URI.create(publicOrigin);
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getPort() != -1 || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || Arrays.stream(allowedOrigins.split(",")).map(String::strip).noneMatch(publicOrigin::equals)) {
            throw new IllegalStateException("Configurá un origen HTTPS de retorno incluido en AUTH_ALLOWED_ORIGINS.");
        }
        this.token = token;
        this.collector = Long.toString(collector);
        this.publicOrigin = publicOrigin;
        this.clock = clock;
        this.client = new Client(transport);
    }

    @Override
    public Preference create(Checkout checkout) throws Uncertain, Unavailable {
        try {
            var account = exchange("/users/me", HttpMethod.GET, null, null, Map.of());
            if (!isTestCollector(account) || !clock.instant().isBefore(expiration(checkout))) {
                throw new Unavailable();
            }
        } catch (Uncertain | RuntimeException error) {
            // No preference has been sent.
            throw new Unavailable();
        }
        var result = exchange(PREFERENCES, HttpMethod.POST, payload(checkout), null,
                Map.of("X-Idempotency-Key", checkout.idempotencyKey().toString()));
        return preference(checkout, result).orElseThrow(Uncertain::new);
    }

    @Override
    public Optional<Preference> recover(Checkout checkout) throws Uncertain {
        var search = exchange(PREFERENCES + "/search", HttpMethod.GET, null,
                Map.of("external_reference", checkout.reference(), "site_id", "MLA", "limit", 2), Map.of());
        try {
            var rows = search.getAsJsonArray("elements");
            if (!"1".equals(value(search, "total")) || rows == null || rows.size() != 1) return Optional.empty();
            var id = value(rows.get(0).getAsJsonObject(), "id");
            if (!id.matches("[A-Za-z0-9_-]{16,80}")) return Optional.empty();
            var candidate = exchange(PREFERENCES + "/" + id, HttpMethod.GET, null, null, Map.of());
            return preference(checkout, candidate).filter(found -> found.id().equals(id));
        } catch (RuntimeException malformed) {
            throw new Uncertain();
        }
    }

    @Override
    public Optional<Payment> lookup(Checkout checkout) throws Uncertain {
        var search = exchange(PAYMENTS + "/search", HttpMethod.GET, null,
                Map.of("external_reference", checkout.reference(), "limit", 10), Map.of());
        try {
            var rows = search.getAsJsonArray("results");
            var total = search.getAsJsonObject("paging").get("total").getAsInt();
            if (rows == null || total != rows.size()) return Optional.empty();
            String approvedId = null;
            String pendingId = null;
            for (var entry : rows) {
                var row = entry.getAsJsonObject();
                if (!checkout.reference().equals(value(row, "external_reference"))) return Optional.empty();
                var status = value(row, "status");
                if (!"approved".equals(status) && !PENDING.contains(status)) continue;
                var id = value(row, "id");
                if (!id.matches("[0-9]{1,30}")) return Optional.empty();
                if ("approved".equals(status)) {
                    if (approvedId != null) return Optional.empty();
                    approvedId = id;
                } else {
                    pendingId = id;
                }
            }
            var id = approvedId == null ? pendingId : approvedId;
            if (id == null) return Optional.empty();
            return lookupPayment(id).filter(found -> checkout.reference().equals(found.reference())
                    && checkout.currency().equals(found.currency()) && checkout.amount().compareTo(found.amount()) == 0);
        } catch (PaymentProviderReadFailure | RuntimeException malformed) {
            throw new Uncertain();
        }
    }

    @Override
    public Optional<Payment> lookupPayment(String id) throws PaymentProviderReadFailure {
        if (id == null || !id.matches("[0-9]{1,30}")) return Optional.empty();
        try {
            var payment = client.exchange(PAYMENTS + "/" + id, HttpMethod.GET, null, null, options(Map.of()));
            var reference = value(payment, "external_reference");
            var currency = value(payment, "currency_id");
            var amount = payment.get("transaction_amount").getAsBigDecimal();
            if (!matchesPayment(payment, id) || reference.isBlank() || amount.signum() <= 0) {
                return Optional.empty();
            }
            var status = value(payment, "status");
            if ("approved".equals(status) && "accredited".equals(value(payment, "status_detail"))) {
                return Optional.of(new Payment(id, reference, amount, currency, Status.APROBADO));
            }
            if (PENDING.contains(status)) return Optional.of(new Payment(id, reference, amount, currency, Status.PENDIENTE));
            if ("rejected".equals(status) || "cancelled".equals(status)) {
                return Optional.of(new Payment(id, reference, amount, currency, Status.RECHAZADO));
            }
            return Optional.empty();
        } catch (MPApiException error) {
            throw new PaymentProviderReadFailure(error.getStatusCode());
        } catch (MPException | RuntimeException malformed) {
            throw new PaymentProviderReadFailure(0);
        }
    }

    private boolean matchesPayment(JsonObject payment, String id) {
        return id.equals(value(payment, "id")) && collector.equals(value(payment, "collector_id"))
                && "ARS".equals(value(payment, "currency_id"))
                && "account_money".equals(value(payment, "payment_type_id"))
                && "account_money".equals(value(payment, "payment_method_id"));
    }

    private boolean isTestCollector(JsonObject account) {
        var tags = account.getAsJsonArray("tags");
        return collector.equals(value(account, "id")) && "MLA".equals(value(account, "site_id"))
                && tags != null && tags.asList().stream().anyMatch(tag -> "test_user".equals(tag.getAsString()));
    }

    @Override public Method refundMethod() { return Method.CHECKOUT_PRO; }

    @Override
    public Observation inspect(Charge charge) throws PaymentProviderReadFailure {
        if (!validRefundCharge(charge)) return MercadoPagoRefunds.REVIEW;
        try {
            String path = PAYMENTS + "/" + charge.paymentId();
            var payment = client.exchange(path, HttpMethod.GET, null, null, options(Map.of()));
            if (!matchesPayment(payment, charge.paymentId()) || !charge.reference().equals(value(payment, "external_reference"))
                    || charge.amount().compareTo(payment.get("transaction_amount").getAsBigDecimal()) != 0) {
                return MercadoPagoRefunds.REVIEW;
            }
            var history = client.exchangeRaw(path + "/refunds", HttpMethod.GET, null, null, options(Map.of()));
            var available = "approved".equals(value(payment, "status")) && "accredited".equals(value(payment, "status_detail"));
            return MercadoPagoRefunds.read(history.getAsJsonArray(), charge, "payment_id", "approved", available);
        } catch (MPApiException error) {
            throw new PaymentProviderReadFailure(error.getStatusCode());
        } catch (MPException | RuntimeException error) {
            throw new PaymentProviderReadFailure(0);
        }
    }

    @Override
    public Observation refund(Charge charge) throws PaymentProviderReadFailure {
        if (!validRefundCharge(charge)) return MercadoPagoRefunds.REVIEW;
        try {
            var account = client.exchange("/users/me", HttpMethod.GET, null, null, options(Map.of()));
            if (!isTestCollector(account)) return MercadoPagoRefunds.REVIEW;
            var reply = client.exchange(PAYMENTS + "/" + charge.paymentId() + "/refunds", HttpMethod.POST,
                    new JsonObject(), null, options(Map.of("X-Idempotency-Key", charge.key().toString())));
            return MercadoPagoRefunds.read(reply, charge, "payment_id", "approved");
        } catch (MPApiException error) {
            throw new PaymentProviderReadFailure(error.getStatusCode());
        } catch (MPException | RuntimeException error) {
            throw new PaymentProviderReadFailure(0);
        }
    }

    private static boolean validRefundCharge(Charge charge) {
        return charge.orderId().equals(charge.paymentId()) && charge.paymentId().matches("[0-9]{1,30}")
                && "ARS".equals(charge.currency());
    }

    private Optional<Preference> preference(Checkout checkout, JsonObject candidate) throws Uncertain {
        try {
            var id = value(candidate, "id");
            var url = value(candidate, "init_point");
            var items = candidate.getAsJsonArray("items");
            if (!id.matches("[A-Za-z0-9_-]{16,80}") || !allowedCheckoutUrl(url, id)
                    || !checkout.reference().equals(value(candidate, "external_reference"))
                    || !collector.equals(value(candidate, "collector_id")) || !"true".equals(value(candidate, "expires"))
                    || !expiration(checkout).equals(Instant.parse(value(candidate, "expiration_date_to")))
                    || items == null || items.size() != 1) return Optional.empty();
            var item = items.get(0).getAsJsonObject();
            var backUrls = candidate.getAsJsonObject("back_urls");
            var back = returnUrl(checkout);
            if (!"1".equals(value(item, "quantity")) || !checkout.currency().equals(value(item, "currency_id"))
                    || checkout.amount().compareTo(item.get("unit_price").getAsBigDecimal()) != 0
                    || backUrls == null || !List.of("success", "pending", "failure").stream()
                            .allMatch(status -> back.equals(value(backUrls, status)))) return Optional.empty();
            return Optional.of(new Preference(id, url));
        } catch (RuntimeException malformed) {
            throw new Uncertain();
        }
    }

    private JsonObject payload(Checkout checkout) {
        var item = new JsonObject();
        item.addProperty("id", checkout.orderId().toString());
        item.addProperty("title", "Compra en Zonda Market");
        item.addProperty("quantity", 1);
        item.addProperty("currency_id", checkout.currency());
        item.addProperty("unit_price", checkout.amount());
        var items = new JsonArray();
        items.add(item);

        var exclusions = new JsonArray();
        for (String type : EXCLUDED_TYPES) {
            var excluded = new JsonObject();
            excluded.addProperty("id", type);
            exclusions.add(excluded);
        }
        var methods = new JsonObject();
        methods.add("excluded_payment_types", exclusions);
        methods.addProperty("default_payment_method_id", "account_money");

        var back = new JsonObject();
        for (String status : List.of("success", "pending", "failure")) back.addProperty(status, returnUrl(checkout));
        var payload = new JsonObject();
        payload.add("items", items);
        payload.add("payment_methods", methods);
        payload.add("back_urls", back);
        payload.addProperty("external_reference", checkout.reference());
        payload.addProperty("purpose", "wallet_purchase");
        payload.addProperty("auto_return", "approved");
        payload.addProperty("expires", true);
        payload.addProperty("expiration_date_from", clock.instant().truncatedTo(ChronoUnit.MILLIS).toString());
        payload.addProperty("expiration_date_to", expiration(checkout).toString());
        payload.addProperty("notification_url", publicOrigin + "/api/v1/webhooks/mercadopago?source_news=webhooks");
        return payload;
    }

    private String returnUrl(Checkout checkout) {
        return publicOrigin + "/checkout/resultado/" + checkout.orderId();
    }

    private static Instant expiration(Checkout checkout) {
        return checkout.expiresAt().truncatedTo(ChronoUnit.MILLIS);
    }

    private static boolean allowedCheckoutUrl(String url, String preference) {
        var target = URI.create(url);
        return url.length() <= 2048 && "https".equals(target.getScheme()) && target.getHost() != null
                && CHECKOUT_HOSTS.contains(target.getHost()) && target.getPort() == -1 && target.getUserInfo() == null
                && CHECKOUT_PATHS.contains(target.getRawPath()) && target.getRawFragment() == null
                && target.getRawQuery() != null && Arrays.stream(target.getRawQuery().split("&"))
                        .filter(param -> param.startsWith("pref_id=")).toList().equals(List.of("pref_id=" + preference));
    }

    private JsonObject exchange(String path, HttpMethod method, JsonObject payload, Map<String, Object> query,
                                Map<String, String> headers) throws Uncertain {
        try {
            return client.exchange(path, method, payload, query, options(headers));
        } catch (MPException | MPApiException | RuntimeException error) {
            throw new Uncertain();
        }
    }

    private MPRequestOptions options(Map<String, String> headers) {
        return MPRequestOptions.builder().accessToken(token).customHeaders(headers)
                .connectionTimeout(1_500).connectionRequestTimeout(1_000).socketTimeout(3_500).maxRetries(0).build();
    }

    private static String value(JsonObject object, String name) {
        var field = object.get(name);
        return field != null && field.isJsonPrimitive() ? field.getAsString() : "";
    }

    private static MPHttpClient transport() {
        MercadoPagoConfig.setLoggingLevel(Level.OFF);
        return new MPDefaultHttpClient();
    }

    private static final class Client extends MercadoPagoClient {
        Client(MPHttpClient transport) { super(transport); }

        JsonObject exchange(String path, HttpMethod method, JsonObject payload, Map<String, Object> query,
                            MPRequestOptions options) throws MPException, MPApiException {
            return exchangeRaw(path, method, payload, query, options).getAsJsonObject();
        }

        JsonElement exchangeRaw(String path, HttpMethod method, JsonObject payload, Map<String, Object> query,
                                MPRequestOptions options) throws MPException, MPApiException {
            return JsonParser.parseString(send(path, method, payload, query, options).getContent());
        }
    }
}
