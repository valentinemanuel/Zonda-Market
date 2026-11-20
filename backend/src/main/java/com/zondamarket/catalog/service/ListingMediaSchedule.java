package com.zondamarket.catalog.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zonda.media.worker-enabled", havingValue = "true", matchIfMissing = true)
class ListingMediaSchedule {
    private final ListingMediaCleanup cleanup;

    ListingMediaSchedule(ListingMediaCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelay = 2000)
    void process() {
        cleanup.deleteNext();
    }
}
