package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.DisputeDtos.Status;
import com.zondamarket.checkout.dto.DisputeResolutionDtos;
import com.zondamarket.checkout.dto.DisputeResolutionDtos.Balance;
import com.zondamarket.checkout.dto.DisputeResolutionDtos.Outcome;
import com.zondamarket.checkout.dto.DisputeResolutionDtos.ResolveRequest;
import com.zondamarket.checkout.dto.DisputeResolutionDtos.Resolution;
import com.zondamarket.checkout.repository.DisputeResolutionStore;
import com.zondamarket.checkout.repository.DisputeResolutionStore.*;
import com.zondamarket.checkout.repository.DisputeStore;
import com.zondamarket.checkout.repository.FulfillmentStore;
import com.zondamarket.shared.errors.ApiProblemException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisputeResolutionService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final DisputeResolutionStore store;
    private final DisputeStore disputes;
    private final FulfillmentStore fulfillment;
    private final ObjectProvider<LocalPaymentPort> local;
    private final Clock clock;

    DisputeResolutionService(DisputeResolutionStore store, DisputeStore disputes, FulfillmentStore fulfillment,
                             ObjectProvider<LocalPaymentPort> local, Clock clock) {
        this.store = store;
        this.disputes = disputes;
        this.fulfillment = fulfillment;
        this.local = local;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Balance balance(UUID dispute) {
        var ctx = context(dispute);
        var capture = capture(ctx);
        var sale = store.sale(ctx.subOrderId()).orElse(null);
        var totals = store.totals(ctx);
        var sub = sale == null ? ZERO : subTotal(sale).subtract(totals.subConfirmed()).subtract(totals.subHeld()).max(ZERO);
        var payment = capture == null ? ZERO : capture.amount().subtract(totals.paymentConfirmed()).subtract(totals.paymentHeld()).max(ZERO);
        return new Balance(sub.toPlainString(), payment.toPlainString(), sub.min(payment).toPlainString(), "ARS");
    }

    @Transactional
    public Resolution resolve(UUID dispute, UUID actor, UUID key, ResolveRequest request, UUID trace) {
        var ctx = context(dispute);
        var order = store.lockOrder(ctx.orderId());
        var sub = fulfillment.lockSubOrder(ctx.subOrderId());
        var current = disputes.visible(dispute, actor, true, true).orElseThrow(DisputeResolutionService::missing);
        var reason = request.reason().strip();
        boolean refund = request.outcome() == Outcome.REEMBOLSO;
        if (reason.isEmpty() || reason.indexOf(0) >= 0 || refund != (request.amount() != null)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Revisá el monto y el motivo de la resolución.");
        }
        var replay = store.replay(dispute, key).orElse(null);
        if (replay != null) {
            var original = store.result(replay.id());
            boolean sameAmount = request.amount() == null ? original.amount() == null
                    : original.amount() != null && request.amount().compareTo(new BigDecimal(original.amount())) == 0;
            if (!actor.equals(replay.actorId()) || replay.expectedVersion() != request.expectedVersion().intValueExact()
                    || original.outcome() != request.outcome() || !original.reason().equals(reason) || !sameAmount) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "La clave ya se usó para otra resolución.");
            }
            return original;
        }
        if (current.version() != request.expectedVersion().intValueExact()) {
            throw conflict("DISPUTE_VERSION_CONFLICT", "El reclamo cambió. Consultá el estado actual.");
        }
        if (current.summary().status() != Status.EN_MEDIACION) {
            throw conflict("DISPUTE_RESOLUTION_CONFLICT", "El reclamo debe estar en mediación.");
        }
        Capture charged = null;
        if (refund) {
            charged = capture(ctx);
            if (!paid(order.status()) || "REEMBOLSADA".equals(sub.status()) || charged == null
                    || charged.amount().compareTo(order.total()) != 0) {
                throw conflict("REFUND_CAPTURE_UNAVAILABLE", "El cobro ganador no está disponible para devoluciones.");
            }
            var sale = store.sale(ctx.subOrderId()).orElseThrow(() ->
                    conflict("REFUND_CAPTURE_UNAVAILABLE", "No encontramos el asiento de la suborden."));
            var totals = store.totals(ctx);
            var paymentAvailable = charged.amount().subtract(totals.paymentConfirmed()).subtract(totals.paymentHeld());
            var subAvailable = subTotal(sale).subtract(totals.subConfirmed()).subtract(totals.subHeld());
            if (request.amount().compareTo(paymentAvailable.min(subAvailable)) > 0) {
                throw conflict("REFUND_BALANCE_EXCEEDED", "El monto supera el saldo disponible.");
            }
        }
        UUID id = UUID.randomUUID();
        var now = now();
        store.insert(id, ctx, actor, key, request, reason, charged, trace, now);
        disputes.transition(dispute, refund ? Status.REEMBOLSO_PENDIENTE : Status.CERRADA_SIN_REEMBOLSO);
        if (!refund) fulfillment.event(ctx.orderId(), ctx.subOrderId(), "DISPUTE_RESOLVED", actor, trace, now);
        return store.result(id);
    }

    @Transactional
    public void finish(Work work, DisputeResolutionDtos.Status status, String reference) {
        var ctx = new Context(work.orderId(), work.subOrderId(), work.disputeId());
        store.lockOrder(ctx.orderId());
        fulfillment.lockSubOrder(ctx.subOrderId());
        var current = disputes.visible(ctx.disputeId(), work.actorId(), true, true).orElseThrow();
        if (current.summary().status() != Status.REEMBOLSO_PENDIENTE) return;
        var now = now();
        if (!store.save(work, status, reference, now, now)) return;
        if (status == DisputeResolutionDtos.Status.RECHAZADA) {
            disputes.transition(ctx.disputeId(), Status.EN_MEDIACION);
            return;
        }

        var sale = store.sale(ctx.subOrderId()).orElseThrow();
        var totals = store.totals(ctx);
        var merchandise = work.amount().min(sale.merchandise().subtract(totals.merchandiseConfirmed()).max(ZERO));
        var shipping = work.amount().subtract(merchandise);
        var remainingShipping = sale.shipping().subtract(totals.subConfirmed().subtract(totals.merchandiseConfirmed()));
        if (shipping.compareTo(remainingShipping) > 0
                || totals.paymentConfirmed().add(work.amount()).compareTo(work.captured()) > 0) {
            throw new IllegalStateException("La devolución verificada supera el saldo contable reservado.");
        }

        // Allocate merchandise before shipping. Reverse the frozen commission proportionally,
        // using the cumulative merchandise amount to absorb the last cent at a full refund.
        var cumulative = totals.merchandiseConfirmed().add(merchandise);
        var commission = sale.commission().multiply(cumulative).divide(sale.merchandise(), 2, RoundingMode.HALF_UP)
                .subtract(totals.commissionReversed()).max(ZERO).min(merchandise);
        store.adjustment(work, merchandise, shipping, commission, now);
        store.refunded(work, totals.subConfirmed().add(work.amount()).compareTo(subTotal(sale)) == 0,
                totals.paymentConfirmed().add(work.amount()).compareTo(work.captured()) == 0, now);
        disputes.transition(ctx.disputeId(), Status.CERRADA_REEMBOLSADA);
        fulfillment.event(ctx.orderId(), ctx.subOrderId(), "DISPUTE_RESOLVED", work.actorId(), work.traceId(), now);
    }

    private Capture capture(Context ctx) {
        return store.capture(ctx.orderId()).filter(c -> c.method() != null || (c.simulated() && local.getIfAvailable() != null))
                .orElse(null);
    }

    private Context context(UUID dispute) { return store.context(dispute).orElseThrow(DisputeResolutionService::missing); }

    static boolean paid(String status) { return "PAGADA".equals(status) || "REEMBOLSO_PARCIAL".equals(status); }

    private static BigDecimal subTotal(Sale sale) { return sale.merchandise().add(sale.shipping()); }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static ApiProblemException missing() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND", "No encontramos ese reclamo.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
