package com.zondamarket.identity.service;

import com.zondamarket.identity.repository.IdentityEmailStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zonda.mail.worker-enabled", havingValue = "true", matchIfMissing = true)
class IdentityMailSchedule {
    private final IdentityMailOutbox outbox;
    private final IdentityEmailStore store;
    private final Clock clock;

    IdentityMailSchedule(IdentityMailOutbox outbox, IdentityEmailStore store, Clock clock) {
        this.outbox = outbox;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    void deliver() {
        for (int sent = 0; sent < 5 && outbox.deliverNext(); sent++) {
            // Bound each poll so maintenance and later polls can run.
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void discardStale() {
        store.discardStale(clock.instant());
    }
}
