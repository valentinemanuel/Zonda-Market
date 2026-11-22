package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.PaymentAttemptResponse;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile(LocalPaymentPort.PROFILES)
public class LocalAttemptFixture {
    public enum Outcome { PENDIENTE, APROBADO, RECHAZADO }

    private final PaymentAttemptService payments;
    private final PaymentReconciler reconciler;

    LocalAttemptFixture(PaymentAttemptService payments, PaymentReconciler reconciler) {
        this.payments = payments;
        this.reconciler = reconciler;
    }

    public PaymentAttemptResponse verify(UUID buyer, UUID order, UUID attemptId, Outcome outcome, UUID trace) {
        payments.get(buyer, order, attemptId);
        var attempt = payments.find(order, attemptId);
        reconciler.apply(buyer, order, attemptId, new VerifiedPayment(attempt.externalReference(),
                attempt.amount(), attempt.currency(), Status.valueOf(outcome.name())), trace);
        return payments.get(buyer, order, attemptId);
    }
}
