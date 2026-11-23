package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.DisputeResolutionDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.repository.DisputeResolutionStore;
import com.zondamarket.checkout.repository.DisputeResolutionStore.Work;
import com.zondamarket.checkout.service.PaymentRefundGateway.*;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DisputeRefundWorker {
    private static final Duration SEND_WINDOW = Duration.ofHours(23);
    private final Map<Method, PaymentRefundGateway> gateways = new EnumMap<>(Method.class);
    private final DisputeResolutionStore store;
    private final DisputeResolutionService resolutions;
    private final ObjectProvider<LocalPaymentPort> local;
    private final Clock clock;

    DisputeRefundWorker(DisputeResolutionStore store, DisputeResolutionService resolutions,
                        ObjectProvider<LocalPaymentPort> local, Clock clock, List<PaymentRefundGateway> gateways) {
        this.store = store;
        this.resolutions = resolutions;
        this.local = local;
        this.clock = clock;
        for (var gateway : gateways) this.gateways.put(gateway.refundMethod(), gateway);
    }

    public boolean processNext() {
        var work = store.claim(clock.instant()).orElse(null);
        if (work == null) return false;
        try {
            process(work);
        } catch (PaymentProviderReadFailure error) {
            boolean transientFailure = error.retryable() || "PROVIDER_HTTP_409".equals(error.code());
            defer(work, transientFailure && maySend(work) ? Status.PENDIENTE : Status.EN_REVISION, null);
        }
        return true;
    }

    private void process(Work work) throws PaymentProviderReadFailure {
        if (work.method() == null && local.getIfAvailable() != null) {
            resolutions.finish(work, Status.CONFIRMADA, "local-refund-" + work.id());
            return;
        }
        var gateway = work.method() == null ? null : gateways.get(work.method());
        if (gateway == null) {
            defer(work, Status.PENDIENTE, null);
            return;
        }

        var charge = new Charge(work.id(), work.providerOrderId(), work.paymentId(), work.paymentReference(), work.amount(), "ARS");
        var partial = new PartialCharge(charge, work.captured(), work.providerRefundId(),
                store.knownReferences(work), work.sentAt() != null);
        var observed = gateway.inspectPartial(partial);
        if (observed.state() == State.AVAILABLE && maySend(work)) {
            if (!store.markSent(work, clock.instant())) return;
            observed = gateway.refundPartial(partial);
        }
        String reference = observed.refundId();
        if (reference != null && work.providerRefundId() != null && !reference.equals(work.providerRefundId())) {
            defer(work, Status.EN_REVISION, null);
            return;
        }
        if (reference != null && observed.state() == State.CONFIRMED) {
            resolutions.finish(work, Status.CONFIRMADA, reference);
        } else if (observed.state() == State.REJECTED) {
            resolutions.finish(work, Status.RECHAZADA, reference);
        } else {
            defer(work, observed.state() == State.REVIEW || !maySend(work) ? Status.EN_REVISION : Status.PENDIENTE,
                    observed.state() == State.PENDING ? reference : null);
        }
    }

    private boolean maySend(Work work) {
        return work.sentAt() == null || (work.status() == Status.PENDIENTE
                && clock.instant().isBefore(work.sentAt().toInstant().plus(SEND_WINDOW)));
    }

    private void defer(Work work, Status status, String reference) {
        var now = clock.instant();
        long delay = status == Status.EN_REVISION ? 900 : Math.min(900, 5L << Math.min(work.attempts() - 1, 8));
        store.save(work, status, reference, now, now.plusSeconds(delay));
    }
}
