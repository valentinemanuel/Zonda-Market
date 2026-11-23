package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.CompensationDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.repository.PaymentCompensationStore;
import com.zondamarket.checkout.repository.PaymentCompensationStore.Work;
import com.zondamarket.checkout.service.PaymentRefundGateway.Charge;
import com.zondamarket.checkout.service.PaymentRefundGateway.Observation;
import com.zondamarket.checkout.service.PaymentRefundGateway.State;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentCompensationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentCompensationWorker.class);
    private static final Duration RETRY_WINDOW = Duration.ofDays(7);
    private final PaymentCompensationStore store;
    private final Clock clock;
    private final Map<Method, PaymentRefundGateway> gateways = new EnumMap<>(Method.class);

    PaymentCompensationWorker(PaymentCompensationStore store, Clock clock, List<PaymentRefundGateway> gateways) {
        this.store = store;
        this.clock = clock;
        for (var gateway : gateways) {
            if (this.gateways.putIfAbsent(gateway.refundMethod(), gateway) != null) {
                throw new IllegalStateException("Hay más de un adaptador de devolución para " + gateway.refundMethod());
            }
        }
    }

    public boolean processNext() {
        if (gateways.isEmpty()) return false;
        var work = store.claim(gateways.keySet(), clock.instant()).orElse(null);
        if (work == null) return false;
        try {
            var gateway = gateways.get(work.method());
            var charge = new Charge(work.id(), work.providerOrderId(), work.providerPaymentId(),
                    work.externalReference(), work.amount(), work.currency());
            // Always read the provider's history first, including after a lost POST response.
            var seen = gateway.inspect(charge);
            if (seen.state() == State.AVAILABLE && work.providerRefundId() == null
                    && !work.reconcileOnly() && retryWindowOpen(work)) {
                seen = gateway.refund(charge);
            }
            apply(work, seen);
        } catch (PaymentProviderReadFailure error) {
            boolean retryable = error.retryable() || "PROVIDER_HTTP_409".equals(error.code());
            save(work, null, error.code(), retryable);
        }
        return true;
    }

    private void apply(Work work, Observation seen) {
        var providerId = seen.refundId();
        if (providerId != null && work.providerRefundId() != null && !providerId.equals(work.providerRefundId())) {
            save(work, null, "REFUND_ID_MISMATCH", false);
            return;
        }
        if (seen.state() == State.CONFIRMED && providerId != null) {
            var now = clock.instant();
            store.save(work, Status.CONFIRMADA, providerId, null, now, now);
        } else if (seen.state() == State.REVIEW) {
            save(work, providerId, "REFUND_REQUIRES_REVIEW", false);
        } else {
            save(work, providerId, null, true);
        }
    }

    private boolean retryWindowOpen(Work work) {
        return clock.instant().isBefore(work.createdAt().plus(RETRY_WINDOW));
    }

    private void save(Work work, String providerId, String failure, boolean retryable) {
        var now = clock.instant();
        if (retryable && !work.reconcileOnly() && retryWindowOpen(work)) {
            long delay = Math.min(900, 5L << Math.min(work.attempts() - 1, 8));
            store.save(work, Status.PENDIENTE, providerId, failure, now, now.plusSeconds(delay));
        } else {
            String reason = failure;
            if (reason == null) reason = work.reconcileOnly() ? "REFUND_NOT_CONFIRMED" : "REFUND_RETRY_WINDOW_ELAPSED";
            store.save(work, Status.EN_REVISION, providerId, reason, now, now);
            LOG.warn("Devolución en revisión traceId={} refundId={} motivo={}", work.traceId(), work.id(), reason);
        }
    }
}
