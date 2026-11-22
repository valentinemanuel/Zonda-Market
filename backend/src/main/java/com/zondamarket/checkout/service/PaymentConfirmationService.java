package com.zondamarket.checkout.service;

import com.zondamarket.cart.repository.CartStore;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentOutcomeStore;
import com.zondamarket.checkout.repository.PaymentOutcomeStore.Result;
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
    private final Clock clock;

    PaymentConfirmationService(CartStore carts, CheckoutStore orders, ReservationStore reservations,
                               PaymentOutcomeStore outcomes, PaymentAttemptStore attempts, Clock clock) {
        this.carts = carts;
        this.orders = orders;
        this.reservations = reservations;
        this.outcomes = outcomes;
        this.attempts = attempts;
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
        if (attempt != null) PaymentReconciler.validate(attempt, verified);
        var previous = outcomes.find(orderId);
        if (previous.isPresent()) {
            var saved = previous.get();
            if (!saved.reference().equals(outcome.reference()) || saved.outcome() != outcome.outcome()) {
                throw conflict("PAYMENT_OUTCOME_CONFLICT", "La referencia ya tiene otro resultado.");
            }
            return;
        }
        if (attempt == null) {
            if (attempts.latest(orderId).filter(a -> a.status().active()).isPresent()) {
                throw conflict("PAYMENT_ATTEMPT_ACTIVE", "Ya hay un intento activo para esta orden.");
            }
        } else {
            if (!attempt.status().active() || attempt.status() == Status.CREADO) {
                throw conflict("PAYMENT_OUTCOME_CONFLICT", "El intento no admite ese resultado.");
            }
        }
        boolean active = "RESERVADA".equals(order.status()) || "PAGO_PENDIENTE".equals(order.status());
        if (!active && !"EXPIRADA".equals(order.status())) {
            throw conflict("PAYMENT_ORDER_CLOSED", "La orden ya tiene un estado definitivo.");
        }
        if (active) reservations.lockResources(orderId);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean approval = outcome.outcome() == Outcome.APPROVED;

        Result result;
        if (!active || !now.isBefore(order.expiresAt())) {
            if (active) reservations.release(orderId, trace, now);
            if (approval) {
                var event = verified != null && verified.providerOrderId() != null
                        ? "CARD_PAYMENT_LATE" : "LOCAL_PAYMENT_LATE";
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
        outcomes.insert(orderId, outcome, result, trace, now);
        if (attemptId != null) {
            if (verified.providerOrderId() != null) {
                attempts.bindProviderOrder(orderId, attemptId, verified.providerOrderId(), now);
            }
            attempts.update(orderId, attemptId, Status.APROBADO, trace, now);
        }
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
