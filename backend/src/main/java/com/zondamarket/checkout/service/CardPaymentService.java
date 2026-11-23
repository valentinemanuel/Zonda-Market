package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.PaymentAttemptResponse;
import com.zondamarket.checkout.dto.PaymentDtos.Provider;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore.Attempt;
import com.zondamarket.checkout.service.CardPaymentGateway.Charge;
import com.zondamarket.checkout.service.CardPaymentGateway.NoCharge;
import com.zondamarket.checkout.service.CardPaymentGateway.SubmissionUncertain;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import com.zondamarket.checkout.shared.PaymentErrors;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CardPaymentService {
    private final PaymentAttemptService payments;
    private final PaymentAttemptStore attempts;
    private final CheckoutStore orders;
    private final CardPaymentGateway card;
    private final PaymentReconciler reconciler;
    private final TransactionTemplate transaction;
    private final Clock clock;

    CardPaymentService(PaymentAttemptService payments, PaymentAttemptStore attempts, CheckoutStore orders,
                       ObjectProvider<CardPaymentGateway> card,
                       PaymentReconciler reconciler, PlatformTransactionManager manager, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.orders = orders;
        this.card = card.getIfAvailable();
        this.reconciler = reconciler;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    public PaymentAttemptResponse submit(UUID buyer, UUID order, String key, CardPaymentRequest request, UUID trace) {
        var id = request.attemptId();
        var claim = claim(buyer, order, id, key, request.lastFour(), trace);
        if (claim == null) return payments.get(buyer, order, id);

        var claimed = claim.attempt();
        var charge = new Charge(claimed.providerKey(), claimed.externalReference(), claimed.amount(), claimed.expiresAt(),
                claim.provinceCode());
        try {
            var providerId = card.create(charge, request);
            rememberId(buyer, order, id, providerId);
            reconcile(buyer, order, claimed, providerId, trace);
        } catch (NoCharge rejected) {
            markAfterSubmission(buyer, order, id, Status.RECHAZADO, trace);
        } catch (SubmissionUncertain timeout) {
            markAfterSubmission(buyer, order, id, Status.RESULTADO_DESCONOCIDO, trace);
        }
        return payments.get(buyer, order, id);
    }

    public PaymentAttemptResponse consult(UUID buyer, UUID order, UUID id, UUID trace) {
        var attempt = transaction.execute(tx -> {
            payments.lockOrder(buyer, order);
            return requireCard(order, id);
        });
        if (card != null && attempt.status().active() && attempt.status() != Status.CREADO) {
            reconcile(buyer, order, attempt, attempt.providerOrderId(), trace);
        }
        return payments.get(buyer, order, id);
    }

    private ClaimedCard claim(UUID buyer, UUID order, UUID id, String key, String lastFour, UUID trace) {
        return transaction.execute(tx -> {
            var savedOrder = payments.lockOrder(buyer, order);
            var attempt = requireCard(order, id);
            if (attempt.status() != Status.CREADO) {
                if (attempt.cardRequestKey() != null && !attempt.cardRequestKey().equals(key)) throw reusedKey();
                return null;
            }
            if (card == null) {
                throw PaymentErrors.conflict("PAYMENT_METHOD_UNAVAILABLE", "El pago con tarjeta no está disponible.");
            }
            var now = clock.instant();
            if (!payments.eligible(savedOrder, now)) throw PaymentErrors.ineligible();
            if (attempts.cardKeyInUse(order, key)) throw reusedKey();
            attempts.claimCard(order, id, key, lastFour, trace, now);
            return new ClaimedCard(attempt, orders.frozenProvince(order));
        });
    }

    private Attempt requireCard(UUID order, UUID id) {
        var attempt = payments.find(order, id);
        if (attempt.provider() != Provider.MERCADO_PAGO || attempt.method() != Method.CARD) {
            throw PaymentErrors.attemptNotFound();
        }
        return attempt;
    }

    private void rememberId(UUID buyer, UUID order, UUID id, String providerId) {
        transaction.executeWithoutResult(tx -> {
            payments.lockOrder(buyer, order);
            var current = requireCard(order, id);
            if (current.providerOrderId() == null) {
                attempts.bindProviderOrder(order, id, providerId, clock.instant());
            }
        });
    }

    private void reconcile(UUID buyer, UUID order, Attempt attempt, String providerId, UUID trace) {
        try {
            var result = card.lookup(providerId, attempt.externalReference(), attempt.expiresAt());
            if (result.isPresent()) {
                var verified = result.get();
                if (attempt.amount().compareTo(verified.amount()) == 0) {
                    reconciler.apply(buyer, order, attempt.id(), VerifiedPayment.from(verified), trace);
                    return;
                }
            }
        } catch (SubmissionUncertain timeout) {
            // Only a verified response can settle or reject the attempt.
        }
        markAfterSubmission(buyer, order, attempt.id(), Status.RESULTADO_DESCONOCIDO, trace);
    }

    private void markAfterSubmission(UUID buyer, UUID order, UUID id, Status status, UUID trace) {
        transaction.executeWithoutResult(tx -> {
            payments.lockOrder(buyer, order);
            var attempt = requireCard(order, id);
            boolean rejectedBeforeCharge = status == Status.RECHAZADO
                    && attempt.status() == Status.RESULTADO_DESCONOCIDO && attempt.providerOrderId() == null;
            if (attempt.status() == Status.ENVIADO || rejectedBeforeCharge) {
                attempts.update(order, id, status, trace, clock.instant());
            }
        });
    }

    private static RuntimeException reusedKey() {
        return PaymentErrors.conflict("IDEMPOTENCY_KEY_REUSED", "La clave no corresponde al envío de este intento.");
    }

    private record ClaimedCard(Attempt attempt, String provinceCode) {}
}
