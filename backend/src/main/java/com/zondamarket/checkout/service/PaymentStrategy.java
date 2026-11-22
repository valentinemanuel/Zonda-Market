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

    record VerifiedPayment(String externalReference, BigDecimal amount, String currency, Status status,
                           String providerOrderId, String providerPaymentId) {
        VerifiedPayment(String externalReference, BigDecimal amount, String currency, Status status) {
            this(externalReference, amount, currency, status, null, null);
        }

        static VerifiedPayment from(CardPaymentGateway.Observation payment) {
            return new VerifiedPayment(payment.externalReference(), payment.amount(), payment.currency(), payment.status(),
                    payment.providerOrderId(), payment.providerPaymentId());
        }

        static VerifiedPayment from(CheckoutProGateway.Payment payment) {
            return new VerifiedPayment(payment.reference(), payment.amount(), payment.currency(), payment.status(),
                    payment.id(), payment.id());
        }
    }

    class SubmissionUncertain extends Exception {}
}
