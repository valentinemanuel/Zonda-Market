package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos.Provider;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentTransactionStore;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import com.zondamarket.checkout.shared.PaymentErrors;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentReconciler {
    private final PaymentAttemptService payments;
    private final PaymentAttemptStore attempts;
    private final PaymentConfirmationService confirmation;
    private final PaymentTransactionStore transactions;
    private final Clock clock;

    PaymentReconciler(PaymentAttemptService payments, PaymentAttemptStore attempts,
                      PaymentConfirmationService confirmation, PaymentTransactionStore transactions, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.confirmation = confirmation;
        this.transactions = transactions;
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
        var now = clock.instant();
        recordTransaction(transactions, attempt, verified, trace, now);
        if (attempt.status() == Status.CREADO) {
            throw PaymentErrors.conflict("PAYMENT_OUTCOME_CONFLICT", "El intento no admite ese resultado.");
        }
        if (attempt.provider() == Provider.MERCADO_PAGO) {
            if (!attempt.status().active()) return;
            if (attempt.method() == Method.CHECKOUT_PRO) {
                // A declined payment can leave the same preference usable. Keep the attempt active.
                if (verified.status() == Status.PENDIENTE && attempt.status() == Status.PENDIENTE
                        && attempt.preferenceId() != null) {
                    attempts.update(order, attemptId, Status.ENVIADO, trace, now);
                }
                return;
            }
        }
        if (attempt.status() == verified.status()) return;
        if (!attempt.status().active()
                || (verified.status() != Status.PENDIENTE && verified.status() != Status.RECHAZADO)) {
            throw PaymentErrors.conflict("PAYMENT_OUTCOME_CONFLICT", "El intento no admite ese resultado.");
        }
        if (verified.providerOrderId() != null) {
            attempts.bindProviderOrder(order, attemptId, verified.providerOrderId(), now);
        }
        attempts.update(order, attemptId, verified.status(), trace, now);
    }

    static void validate(PaymentAttemptStore.Attempt attempt, VerifiedPayment verified) {
        if (!attempt.externalReference().equals(verified.externalReference())
                || attempt.amount().compareTo(verified.amount()) != 0
                || !attempt.currency().equals(verified.currency())
                || (attempt.provider() == Provider.MERCADO_PAGO) != (verified.providerOrderId() != null)
                || (attempt.provider() == Provider.MERCADO_PAGO && verified.status() == Status.APROBADO
                    && verified.providerPaymentId() == null)
                || (attempt.method() == Method.CARD && attempt.providerOrderId() != null
                    && !attempt.providerOrderId().equals(verified.providerOrderId()))) {
            throw PaymentErrors.conflict("PAYMENT_ATTEMPT_MISMATCH", "El pago verificado no coincide con el intento.");
        }
    }

    static void recordTransaction(PaymentTransactionStore transactions, PaymentAttemptStore.Attempt attempt,
                                  VerifiedPayment payment, UUID trace, Instant now) {
        if (payment.providerPaymentId() != null && !transactions.record(attempt.method(), payment.providerPaymentId(),
                payment.providerOrderId(), attempt.id(), payment.status(), payment.amount(), payment.currency(), trace, now)) {
            throw PaymentErrors.conflict("PAYMENT_ATTEMPT_MISMATCH", "La transacción ya está asociada a otro intento.");
        }
    }
}
