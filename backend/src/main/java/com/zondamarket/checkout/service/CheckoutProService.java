package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.PaymentAttemptResponse;
import com.zondamarket.checkout.dto.PaymentDtos.Provider;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore.Attempt;
import com.zondamarket.checkout.service.CheckoutProGateway.Checkout;
import com.zondamarket.checkout.service.CheckoutProGateway.Preference;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import com.zondamarket.checkout.shared.PaymentErrors;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckoutProService {
    private final PaymentAttemptService payments;
    private final PaymentAttemptStore attempts;
    private final CheckoutProGateway gateway;
    private final PaymentReconciler reconciler;
    private final Clock clock;
    private final TransactionTemplate transaction;

    CheckoutProService(PaymentAttemptService payments, PaymentAttemptStore attempts,
                       ObjectProvider<CheckoutProGateway> gateway, PaymentReconciler reconciler,
                       PlatformTransactionManager manager, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.gateway = gateway.getIfAvailable();
        this.reconciler = reconciler;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    public PaymentAttemptResponse submit(UUID buyer, UUID order, UUID id, String key, UUID trace) {
        var claimed = transaction.execute(tx -> {
            var savedOrder = payments.lockOrder(buyer, order);
            var attempt = requirePro(order, id);
            if (attempt.proRequestKey() != null && !attempt.proRequestKey().equals(key)) throw reusedKey();
            if (attempt.status() != Status.CREADO) return null;
            if (gateway == null) {
                throw PaymentErrors.conflict("PAYMENT_METHOD_UNAVAILABLE", "El pago con saldo no está disponible.");
            }
            var now = clock.instant();
            if (!payments.eligible(savedOrder, now)) throw PaymentErrors.ineligible();
            if (attempts.proKeyInUse(order, key)) throw reusedKey();
            attempts.claimPro(order, id, key, trace, now);
            return attempt;
        });
        if (claimed != null) {
            try {
                remember(buyer, order, id, gateway.create(checkout(order, claimed)), trace);
            } catch (CheckoutProGateway.Unavailable unavailable) {
                markSubmission(buyer, order, id, Status.RECHAZADO, trace);
            } catch (CheckoutProGateway.Uncertain uncertain) {
                markSubmission(buyer, order, id, Status.RESULTADO_DESCONOCIDO, trace);
            }
        }
        return payments.get(buyer, order, id);
    }

    public PaymentAttemptResponse consult(UUID buyer, UUID order, UUID id, UUID trace) {
        var attempt = transaction.execute(tx -> {
            payments.lockOrder(buyer, order);
            return requirePro(order, id);
        });
        if (gateway == null || !attempt.status().active() || attempt.status() == Status.CREADO) {
            return payments.get(buyer, order, id);
        }
        var checkout = checkout(order, attempt);
        if (attempt.preferenceId() == null) {
            try {
                gateway.recover(checkout).ifPresent(preference -> remember(buyer, order, id, preference, trace));
            } catch (CheckoutProGateway.Uncertain uncertain) {
                // A failed preference lookup does not rule out a payment.
            }
        }
        try {
            var payment = gateway.lookup(checkout).filter(found -> checkout.reference().equals(found.reference())
                    && checkout.currency().equals(found.currency()) && checkout.amount().compareTo(found.amount()) == 0);
            if (payment.isPresent()) {
                reconciler.apply(buyer, order, id, VerifiedPayment.from(payment.get()), trace);
            }
        } catch (CheckoutProGateway.Uncertain uncertain) {
            // A failed read leaves the existing attempt and preference intact.
        }
        return payments.get(buyer, order, id);
    }

    private void remember(UUID buyer, UUID order, UUID id, Preference preference, UUID trace) {
        transaction.executeWithoutResult(tx -> {
            payments.lockOrder(buyer, order);
            var attempt = requirePro(order, id);
            if (attempt.preferenceId() == null && attempt.status().active()) {
                attempts.bindPreference(order, id, preference.id(), preference.url(), trace, clock.instant());
            }
        });
    }

    private void markSubmission(UUID buyer, UUID order, UUID id, Status status, UUID trace) {
        transaction.executeWithoutResult(tx -> {
            payments.lockOrder(buyer, order);
            var attempt = requirePro(order, id);
            if (attempt.status() == Status.ENVIADO && attempt.preferenceId() == null) {
                attempts.update(order, id, status, trace, clock.instant());
            }
        });
    }

    private Attempt requirePro(UUID order, UUID id) {
        var attempt = payments.find(order, id);
        if (attempt.provider() != Provider.MERCADO_PAGO || attempt.method() != Method.CHECKOUT_PRO) {
            throw PaymentErrors.attemptNotFound();
        }
        return attempt;
    }

    private static Checkout checkout(UUID order, Attempt attempt) {
        return new Checkout(order, attempt.providerKey(), attempt.externalReference(), attempt.amount(),
                attempt.currency(), attempt.expiresAt());
    }

    private static RuntimeException reusedKey() {
        return PaymentErrors.conflict("IDEMPOTENCY_KEY_REUSED", "La clave no corresponde al envío de este intento.");
    }
}
