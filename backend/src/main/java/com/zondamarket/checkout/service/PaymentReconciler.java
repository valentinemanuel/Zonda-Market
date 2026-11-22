package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos.Provider;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import com.zondamarket.checkout.shared.PaymentErrors;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentReconciler {
    private final PaymentAttemptService payments;
    private final PaymentAttemptStore attempts;
    private final PaymentConfirmationService confirmation;
    private final Clock clock;

    PaymentReconciler(PaymentAttemptService payments, PaymentAttemptStore attempts,
                      PaymentConfirmationService confirmation, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.confirmation = confirmation;
        this.clock = clock;
    }

    @Transactional(timeout = 5)
    public void apply(UUID buyer, UUID order, UUID attemptId, VerifiedPayment verified, UUID trace) {
        // Approval must acquire the cart lock before the order lock.
        if (verified.status() == Status.APROBADO) {
            confirmation.applyAttemptApproval(buyer, order, attemptId, verified, trace);
            return;
        }
        payments.lockOrder(buyer, order);
        var attempt = payments.find(order, attemptId);
        validate(attempt, verified);
        if (attempt.status() == verified.status()) return;
        if (!attempt.status().active() && verified.providerPaymentId() != null
                && verified.status() == Status.PENDIENTE) return;
        if (!attempt.status().active() || attempt.status() == Status.CREADO
                || (verified.status() != Status.PENDIENTE && verified.status() != Status.RECHAZADO)) {
            throw PaymentErrors.conflict("PAYMENT_OUTCOME_CONFLICT", "El intento no admite ese resultado.");
        }
        var now = clock.instant();
        if (verified.providerPaymentId() != null) {
            attempts.bindProviderPayment(order, attemptId, verified.providerPaymentId(), now);
        }
        attempts.update(order, attemptId, verified.status(), trace, now);
    }

    static void validate(PaymentAttemptStore.Attempt attempt, VerifiedPayment verified) {
        if (!attempt.externalReference().equals(verified.externalReference())
                || attempt.amount().compareTo(verified.amount()) != 0
                || !attempt.currency().equals(verified.currency())
                || (attempt.provider() == Provider.MERCADO_PAGO) != (verified.providerPaymentId() != null)
                || (attempt.providerPaymentId() != null && !attempt.providerPaymentId().equals(verified.providerPaymentId()))) {
            throw PaymentErrors.conflict("PAYMENT_ATTEMPT_MISMATCH", "El pago verificado no coincide con el intento.");
        }
    }
}
