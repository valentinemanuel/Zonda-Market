package com.zondamarket.checkout.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zonda.checkout.expiry.enabled", havingValue = "true", matchIfMissing = true)
class ReservationExpirySchedule {
    private final ReservationExpiry expiry;

    ReservationExpirySchedule(ReservationExpiry expiry) {
        this.expiry = expiry;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${zonda.checkout.expiry.delay-ms:5000}")
    void sweep() {
        expiry.sweep();
    }
}
