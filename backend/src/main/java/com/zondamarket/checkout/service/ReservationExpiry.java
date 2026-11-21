package com.zondamarket.checkout.service;

import com.zondamarket.checkout.repository.ReservationExpiryStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationExpiry {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiry.class);
    private static final int BATCH_SIZE = 20;
    private final ReservationExpiryStore store;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Counter released;
    private final Counter failures;

    ReservationExpiry(ReservationExpiryStore store, Clock clock, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.store = store;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
        released = metrics.counter("zonda.checkout.expiry.released");
        failures = metrics.counter("zonda.checkout.expiry.failures");
    }

    public void forOrder(UUID buyer, UUID order, UUID trace) {
        expire(order, buyer, trace, now(), false);
    }

    public void forBuyer(UUID buyer, UUID trace) {
        Instant cutoff = now();
        store.dueForBuyer(buyer, cutoff).forEach(order -> expire(order, buyer, trace, cutoff, false));
    }

    public void forSku(UUID sku, UUID trace) {
        Instant cutoff = now();
        store.dueForSku(sku, cutoff, BATCH_SIZE).forEach(order -> expire(order, null, trace, cutoff, false));
    }

    public void forCheckout(UUID buyer, UUID trace) {
        forBuyer(buyer, trace);
        Instant cutoff = now();
        store.dueForCart(buyer, cutoff, BATCH_SIZE).forEach(order -> expire(order, null, trace, cutoff, false));
    }

    public void sweep() {
        Instant cutoff = now();
        for (UUID order : store.due(cutoff, BATCH_SIZE)) {
            try {
                expire(order, null, UUID.randomUUID(), cutoff, true);
            } catch (RuntimeException exception) {
                LOG.warn("No se pudo liberar la reserva de la orden {}", order, exception);
            }
        }
    }

    private void expire(UUID order, UUID buyer, UUID trace, Instant cutoff, boolean fromWorker) {
        try {
            boolean changed = Boolean.TRUE.equals(transaction.execute(status -> {
                if (!store.lockDue(order, buyer, cutoff, fromWorker)) return false;
                store.lockResources(order);
                store.release(order, trace, now());
                return true;
            }));
            if (changed) released.increment();
        } catch (RuntimeException exception) {
            failures.increment();
            throw exception;
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
