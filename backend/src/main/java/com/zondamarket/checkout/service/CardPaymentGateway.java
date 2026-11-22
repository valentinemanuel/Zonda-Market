package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CardPaymentGateway {
    String publicKey();

    long create(Charge charge, CardPaymentRequest card) throws SubmissionUncertain, NoCharge;

    Optional<Observation> lookup(Long paymentId, String externalReference) throws SubmissionUncertain;

    record Charge(UUID idempotencyKey, String externalReference, BigDecimal amount, Instant expiresAt) {}
    record Observation(long paymentId, String externalReference, BigDecimal amount, String currency, Status status) {}

    class SubmissionUncertain extends Exception {}
    class NoCharge extends Exception {}
}
