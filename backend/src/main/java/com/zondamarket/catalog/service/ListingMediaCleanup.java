package com.zondamarket.catalog.service;

import com.zondamarket.catalog.repository.ListingMediaStore;
import com.zondamarket.catalog.service.ListingMediaStorage.StorageException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ListingMediaCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(ListingMediaCleanup.class);
    private final ListingMediaStore store;
    private final Optional<ListingMediaStorage> storage;
    private final Clock clock;

    ListingMediaCleanup(ListingMediaStore store, Optional<ListingMediaStorage> storage, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.clock = clock;
    }

    public boolean deleteNext() {
        return storage.map(this::deleteNext).orElse(false);
    }

    private boolean deleteNext(ListingMediaStorage provider) {
        var now = clock.instant();
        var job = store.claim(provider.provider(), now, now.plusSeconds(90)).orElse(null);
        if (job == null) {
            return false;
        }
        try {
            provider.delete(job.publicId());
            store.completed(job, clock.instant());
        } catch (StorageException failure) {
            long delay = Math.min(300, 5L << Math.min(job.attempts() - 1, 6));
            store.retry(job, clock.instant().plusSeconds(delay));
            LOG.warn("Borrado de imagen pendiente publicId={} intento={}", job.publicId(), job.attempts());
        }
        return true;
    }
}
