package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.Listing.Status;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ListingMediaStore {
    public record Cleanup(UUID publicId, UUID lease, int attempts) {}

    private final JdbcClient sql;

    ListingMediaStore(JdbcClient sql) {
        this.sql = sql;
    }

    public void enqueue(UUID publicId, String provider, Instant now, Instant due) {
        sql.sql("""
                insert into zonda_market.listing_media_cleanup (public_id, provider, created_at, next_attempt_at)
                values (:id, :provider, :now, :due)
                on conflict (public_id) do update
                set created_at = excluded.created_at, next_attempt_at = excluded.next_attempt_at, cancelled_at = null
                where listing_media_cleanup.cancelled_at is not null
                """).param("id", publicId).param("provider", provider).param("now", at(now)).param("due", at(due)).update();
    }

    public boolean cancelUploadCleanup(UUID publicId, Instant now) {
        return sql.sql("""
                update zonda_market.listing_media_cleanup set cancelled_at = :now
                where public_id = :id and attempts = 0 and next_attempt_at > :now and cancelled_at is null
                """).param("id", publicId).param("now", at(now)).update() == 1;
    }

    public void expedite(UUID publicId, Instant now) {
        sql.sql("""
                update zonda_market.listing_media_cleanup set next_attempt_at = :now
                where public_id = :id and attempts = 0 and cancelled_at is null
                """).param("id", publicId).param("now", at(now)).update();
    }

    public Optional<Cleanup> claim(String provider, Instant now, Instant until) {
        UUID lease = UUID.randomUUID();
        return sql.sql("""
                with next as (
                    select public_id from zonda_market.listing_media_cleanup
                    where provider = :provider and completed_at is null and cancelled_at is null
                        and next_attempt_at <= :now and (lease_until is null or lease_until <= :now)
                    order by next_attempt_at, public_id limit 1 for update skip locked
                )
                update zonda_market.listing_media_cleanup job
                set lease_id = :lease, lease_until = :until, attempts = job.attempts + 1
                from next where job.public_id = next.public_id
                returning job.public_id, job.attempts
                """).param("provider", provider).param("now", at(now)).param("lease", lease).param("until", at(until))
                .query((row, index) -> new Cleanup(row.getObject("public_id", UUID.class), lease,
                        row.getInt("attempts"))).optional();
    }

    public void completed(Cleanup job, Instant now) {
        sql.sql("""
                update zonda_market.listing_media_cleanup
                set completed_at = :now, lease_id = null, lease_until = null
                where public_id = :id and lease_id = :lease
                """).param("id", job.publicId()).param("lease", job.lease()).param("now", at(now)).update();
    }

    public void retry(Cleanup job, Instant due) {
        sql.sql("""
                update zonda_market.listing_media_cleanup
                set next_attempt_at = :due, lease_id = null, lease_until = null
                where public_id = :id and lease_id = :lease
                """).param("id", job.publicId()).param("lease", job.lease()).param("due", at(due)).update();
    }

    public void recordStatusChange(UUID listing, UUID actor, UUID trace, Status previous, Status next, Instant now) {
        sql.sql("""
                insert into zonda_market.listing_status_change
                    (id, listing_id, actor_id, trace_id, previous_status, new_status, created_at)
                values (:id, :listing, :actor, :trace, :previous, :next, :now)
                """).param("id", UUID.randomUUID()).param("listing", listing).param("actor", actor)
                .param("trace", trace).param("previous", previous.name()).param("next", next.name())
                .param("now", at(now)).update();
    }

    private static OffsetDateTime at(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
