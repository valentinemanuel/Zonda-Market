package com.zondamarket.checkout.service;

import com.zondamarket.cart.repository.CartStore;
import com.zondamarket.checkout.dto.CompensationDtos.Reason;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore.Attempt;
import com.zondamarket.checkout.repository.PaymentCompensationStore;
import com.zondamarket.checkout.repository.PaymentOutcomeStore;
import com.zondamarket.checkout.repository.PaymentOutcomeStore.Recorded;
import com.zondamarket.checkout.repository.PaymentOutcomeStore.Result;
import com.zondamarket.checkout.repository.PaymentTransactionStore;
import com.zondamarket.checkout.repository.ReservationStore;
import com.zondamarket.checkout.service.LocalPaymentPort.Outcome;
import com.zondamarket.checkout.service.LocalPaymentPort.VerifiedOutcome;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import com.zondamarket.checkout.shared.PaymentErrors;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfirmationService {
    private final CartStore carts;
    private final CheckoutStore orders;
    private final ReservationStore reservations;
    private final PaymentOutcomeStore outcomes;
    private final PaymentAttemptStore attempts;
    private final PaymentTransactionStore transactions;
    private final PaymentCompensationStore compensations;
    private final Clock clock;

    PaymentConfirmationService(CartStore carts, CheckoutStore orders, ReservationStore reservations,
                               PaymentOutcomeStore outcomes, PaymentAttemptStore attempts,
                               PaymentTransactionStore transactions, PaymentCompensationStore compensations, Clock clock) {
        this.carts = carts;
        this.orders = orders;
        this.reservations = reservations;
        this.outcomes = outcomes;
        this.attempts = attempts;
        this.transactions = transactions;
        this.compensations = compensations;
        this.clock = clock;
    }

    @Transactional(timeout = 5)
    public void applyVerifiedOutcome(UUID buyer, UUID orderId, VerifiedOutcome outcome, UUID trace) {
        apply(buyer, orderId, outcome, null, null, trace);
    }

    @Transactional(timeout = 5)
    public void applyAttemptApproval(UUID buyer, UUID orderId, UUID attemptId, VerifiedPayment verified, UUID trace) {
        apply(buyer, orderId, new VerifiedOutcome(verified.externalReference(), Outcome.APPROVED,
                verified.amount(), verified.currency()), attemptId, verified, trace);
    }

    private void apply(UUID buyer, UUID orderId, VerifiedOutcome outcome, UUID attemptId,
                       VerifiedPayment verified, UUID trace) {
        // Cart edits and checkout share the cart lock. Expiration never acquires it.
        var cart = carts.lock(buyer);
        var order = orders.lock(buyer, orderId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No encontramos esa orden."));
        if (order.total().compareTo(outcome.amount()) != 0 || !order.currency().equals(outcome.currency())) {
            throw conflict("PAYMENT_AMOUNT_MISMATCH", "El importe verificado no coincide con la orden.");
        }
        var attempt = attemptId == null ? null : attempts.find(orderId, attemptId).orElseThrow(PaymentErrors::attemptNotFound);
        boolean external = verified != null && verified.providerOrderId() != null;
        if (attempt != null) {
            PaymentReconciler.validate(attempt, verified);
            if (attempt.status() == Status.CREADO) {
                throw conflict("PAYMENT_OUTCOME_CONFLICT", "El intento no admite ese resultado.");
            }
            PaymentReconciler.recordTransaction(transactions, attempt, verified, trace, clock.instant());
        }
        var previous = outcomes.find(orderId);
        if (previous.isPresent()) {
            var saved = previous.get();
            if (external) {
                if (saved.result() != Result.APPROVED) enqueue(orderId, attempt, verified, Reason.TARDIO, trace);
                else if (!isWinningPayment(saved, attempt, verified)) enqueue(orderId, attempt, verified, Reason.DUPLICADO, trace);
            } else if (!saved.reference().equals(outcome.reference()) || saved.outcome() != outcome.outcome()) {
                throw conflict("PAYMENT_OUTCOME_CONFLICT", "La referencia ya tiene otro resultado.");
            }
            return;
        }
        if (attempt == null) {
            if (attempts.latest(orderId).filter(a -> a.status().active()).isPresent()) {
                throw conflict("PAYMENT_ATTEMPT_ACTIVE", "Ya hay un intento activo para esta orden.");
            }
        } else if (!attempt.status().active()) {
            if (!external) {
                throw conflict("PAYMENT_OUTCOME_CONFLICT", "El intento no admite ese resultado.");
            }
            enqueue(orderId, attempt, verified, Reason.TARDIO, trace);
            return;
        }
        boolean active = "RESERVADA".equals(order.status()) || "PAGO_PENDIENTE".equals(order.status());
        if (!active && !"EXPIRADA".equals(order.status())) {
            if (external) {
                enqueue(orderId, attempt, verified, Reason.TARDIO, trace);
                return;
            }
            throw conflict("PAYMENT_ORDER_CLOSED", "La orden ya tiene un estado definitivo.");
        }
        if (external && active && clock.instant().isBefore(order.expiresAt()) && attempt.method() == Method.CARD
                && attempt.providerOrderId() != null && !attempt.providerOrderId().equals(verified.providerOrderId())) {
            enqueue(orderId, attempt, verified, Reason.DUPLICADO, trace);
            return;
        }
        if (active) reservations.lockResources(orderId);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean approval = outcome.outcome() == Outcome.APPROVED;

        Result result;
        if (!active || !now.isBefore(order.expiresAt())) {
            if (active) reservations.release(orderId, trace, now);
            if (approval) {
                var event = "LOCAL_PAYMENT_LATE";
                if (external) {
                    event = attempt.method() == Method.CHECKOUT_PRO
                            ? "CHECKOUT_PRO_PAYMENT_LATE" : "CARD_PAYMENT_LATE";
                    enqueue(orderId, attempt, verified, Reason.TARDIO, trace);
                }
                reservations.event(orderId, event, trace, now);
            }
            result = approval ? Result.LATE_APPROVAL : Result.EXPIRED;
        } else if (approval) {
            int consumed = reservations.settle(orderId, true, "PAGADA", "PREPARANDO", "CHECKOUT_PAID", trace, now);
            if (carts.subtractPurchased(cart.id(), orderId) != consumed) {
                throw new IllegalStateException("Las líneas compradas no coinciden con las reservas de la orden.");
            }
            carts.advance(cart.id());
            result = Result.APPROVED;
        } else {
            reservations.settle(orderId, false, "FALLIDA", "CANCELADA", "CHECKOUT_PAYMENT_REJECTED", trace, now);
            result = Result.REJECTED;
        }
        outcomes.insert(orderId, outcome, result, external ? attempt.method() : null,
                external ? verified.providerPaymentId() : null, trace, now);
        if (attemptId != null) {
            if (verified.providerOrderId() != null) {
                attempts.bindProviderOrder(orderId, attemptId, verified.providerOrderId(), now);
            }
            attempts.update(orderId, attemptId, Status.APROBADO, trace, now);
        }
    }

    private static boolean isWinningPayment(Recorded saved, Attempt attempt, VerifiedPayment verified) {
        if (saved.providerPaymentId() != null) {
            return saved.paymentMethod() == attempt.method() && saved.providerPaymentId().equals(verified.providerPaymentId());
        }
        // Older outcomes may only have the winning attempt and its bound provider resource.
        return saved.reference().equals(verified.externalReference())
                && (attempt.providerOrderId() == null || attempt.providerOrderId().equals(verified.providerOrderId()));
    }

    private void enqueue(UUID order, Attempt attempt, VerifiedPayment payment, Reason reason, UUID trace) {
        compensations.enqueue(order, attempt.method(), payment.providerPaymentId(), reason, payment.amount(),
                trace, clock.instant());
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
