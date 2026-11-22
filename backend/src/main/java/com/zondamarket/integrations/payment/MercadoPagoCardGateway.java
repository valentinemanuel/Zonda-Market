package com.zondamarket.integrations.payment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.common.IdentificationRequest;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.payment.PaymentCreateRequest;
import com.mercadopago.client.payment.PaymentPayerRequest;
import com.mercadopago.client.user.UserClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPDefaultHttpClient;
import com.mercadopago.net.MPHttpClient;
import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.serialization.Serializer;
import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.CardPaymentGateway;
import java.time.Clock;
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
    private static final Set<String> CARD_TYPES = Set.of("credit_card", "debit_card");
    private final String publicKey;
    private final String accessToken;
    private final long collector;
    private final CardPaymentsClient payments;
    private final UserClient users;
    private final Clock clock;

    @Autowired
    MercadoPagoCardGateway(@Value("${zonda.mercado-pago.public-key}") String publicKey,
                           @Value("${zonda.mercado-pago.access-token}") String accessToken,
                           @Value("${zonda.mercado-pago.test-collector-id}") long collector, Clock clock) {
        this(publicKey, accessToken, collector, clock, transport());
    }

    MercadoPagoCardGateway(String publicKey, String accessToken, long collector, Clock clock,
                           MPHttpClient http) {
        if (publicKey.isBlank() || accessToken.isBlank() || collector <= 0) {
            throw new IllegalStateException("Configurá las credenciales y el colector de prueba de Mercado Pago.");
        }
        this.publicKey = publicKey;
        this.accessToken = accessToken;
        this.collector = collector;
        this.clock = clock;
        this.payments = new CardPaymentsClient(http);
        this.users = new UserClient(http);
    }

    private static MPHttpClient transport() {
        // The SDK logs request and response bodies at debug level.
        MercadoPagoConfig.setLoggingLevel(Level.OFF);
        return new MPDefaultHttpClient();
    }

    @Override
    public String publicKey() {
        return publicKey;
    }

    @Override
    public long create(Charge charge, CardPaymentRequest card) throws SubmissionUncertain, NoCharge {
        verifyTestCollector();
        if (!clock.instant().isBefore(charge.expiresAt())) throw new NoCharge();
        var payer = PaymentPayerRequest.builder().email(card.payer().email());
        if (card.payer().identification() != null) {
            var identification = card.payer().identification();
            payer.identification(IdentificationRequest.builder().type(identification.type()).number(identification.number()).build());
        }
        var request = PaymentCreateRequest.builder()
                .token(card.token()).paymentMethodId(card.paymentMethodId()).issuerId(card.issuerId())
                .installments(card.installments()).payer(payer.build()).transactionAmount(charge.amount())
                .externalReference(charge.externalReference()).description("Compra en Zonda Market")
                .build();
        try {
            var payment = payments.createWithStableKey(request,
                    options(Map.of("X-Idempotency-Key", charge.idempotencyKey().toString())));
            if (payment == null || payment.getId() == null || payment.getId() <= 0) throw new SubmissionUncertain();
            return payment.getId();
        } catch (MPApiException error) {
            if (rejectedBeforePayment(error)) throw new NoCharge();
            throw new SubmissionUncertain();
        } catch (MPException | RuntimeException error) {
            throw new SubmissionUncertain();
        }
    }

    @Override
    public Optional<Observation> lookup(Long paymentId, String reference) throws SubmissionUncertain {
        try {
            var readOptions = options(Map.of());
            if (paymentId == null) {
                var search = MPSearchRequest.builder().limit(2).offset(0).filters(Map.of(
                        "external_reference", reference, "sort", "date_created", "criteria", "desc",
                        "range", "date_created", "begin_date", "NOW-365DAYS", "end_date", "NOW")).build();
                var results = payments.search(search, readOptions).getResults();
                if (results == null || results.size() != 1 || !reference.equals(results.getFirst().getExternalReference())) {
                    return Optional.empty();
                }
                paymentId = results.getFirst().getId();
                if (paymentId == null || paymentId <= 0) return Optional.empty();
            }
            return observation(payments.get(paymentId, readOptions), paymentId, reference);
        } catch (MPException | MPApiException | RuntimeException error) {
            throw new SubmissionUncertain();
        }
    }

    private void verifyTestCollector() throws NoCharge {
        try {
            var account = users.get(options(Map.of()));
            var body = JsonParser.parseString(account.getResponse().getContent()).getAsJsonObject();
            var tags = body.getAsJsonArray("tags");
            if (!Objects.equals(collector, account.getId()) || !"MLA".equals(account.getSiteId()) || tags == null
                    || tags.asList().stream().noneMatch(tag -> tag.isJsonPrimitive() && "test_user".equals(tag.getAsString()))) {
                throw new NoCharge();
            }
        } catch (MPException | MPApiException | RuntimeException error) {
            // A failed preflight occurs before the payment endpoint is called.
            throw new NoCharge();
        }
    }

    private Optional<Observation> observation(Payment payment, Long id, String reference) {
        if (payment == null || !id.equals(payment.getId()) || !reference.equals(payment.getExternalReference())
                || !Objects.equals(collector, payment.getCollectorId()) || payment.isLiveMode()
                || !"ARS".equals(payment.getCurrencyId()) || payment.getTransactionAmount() == null
                || payment.getPaymentTypeId() == null || !CARD_TYPES.contains(payment.getPaymentTypeId())) {
            return Optional.empty();
        }
        var testMode = JsonParser.parseString(payment.getResponse().getContent()).getAsJsonObject().get("live_mode");
        if (testMode == null || !testMode.isJsonPrimitive() || !testMode.getAsJsonPrimitive().isBoolean()) {
            return Optional.empty();
        }
        var status = switch (Objects.toString(payment.getStatus(), "")) {
            case "approved" -> Status.APROBADO;
            case "rejected", "cancelled" -> Status.RECHAZADO;
            case "pending", "in_process", "authorized", "in_mediation" -> Status.PENDIENTE;
            default -> null;
        };
        return Optional.ofNullable(status).map(value -> new Observation(id, reference,
                payment.getTransactionAmount(), payment.getCurrencyId(), value));
    }

    private static boolean rejectedBeforePayment(MPApiException error) {
        if (error.getStatusCode() != 400 || error.getApiResponse() == null) return false;
        try {
            JsonObject body = JsonParser.parseString(error.getApiResponse().getContent()).getAsJsonObject();
            var causes = body.getAsJsonArray("cause");
            return causes != null && causes.size() == 1
                    && Set.of("3001", "3006", "3011", "3014")
                    .contains(causes.get(0).getAsJsonObject().get("code").getAsString());
        } catch (RuntimeException malformedResponse) {
            return false;
        }
    }

    private MPRequestOptions options(Map<String, String> headers) {
        return MPRequestOptions.builder().accessToken(accessToken).customHeaders(headers)
                .connectionTimeout(1_500).connectionRequestTimeout(1_000).socketTimeout(2_500)
                .maxRetries(0).build();
    }

    private static final class CardPaymentsClient extends PaymentClient {
        CardPaymentsClient(MPHttpClient http) {
            super(http);
        }

        Payment createWithStableKey(PaymentCreateRequest request, MPRequestOptions options) throws MPException, MPApiException {
            // SDK 3.4.0 adds a second key if the header is absent from the request before options are merged.
            var response = send("/v1/payments", HttpMethod.POST, Serializer.serializeToJson(request), null, options);
            return Serializer.deserializeFromJson(Payment.class, response.getContent());
        }
    }
}
