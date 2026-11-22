package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CardPaymentGateway {
    String publicKey();

    String create(Charge charge, CardPaymentRequest card) throws SubmissionUncertain, NoCharge;

    Optional<Observation> lookup(String providerOrderId, String externalReference, Instant expiresAt) throws SubmissionUncertain;

    Optional<Observation> lookupOrder(String providerOrderId) throws PaymentProviderReadFailure;

    record Charge(UUID idempotencyKey, String externalReference, BigDecimal amount, Instant expiresAt, String provinceCode) {}
    record Observation(String providerOrderId, String providerPaymentId, String externalReference, BigDecimal amount,
                       String currency, Status status) {}

    class SubmissionUncertain extends Exception {}
    class NoCharge extends Exception {}
}
