package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

interface PaymentStrategy {
    Set<Method> methods();

    void submit(PaymentCommand command) throws SubmissionUncertain;

    record PaymentCommand(Method method, UUID idempotencyKey, String externalReference, BigDecimal amount,
                          String currency, Instant expiresAt) {}

    record VerifiedPayment(String externalReference, BigDecimal amount, String currency, Status status) {}

    class SubmissionUncertain extends Exception {}
}
