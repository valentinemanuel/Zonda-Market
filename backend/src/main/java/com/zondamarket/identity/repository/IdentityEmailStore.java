package com.zondamarket.identity.repository;

import com.zondamarket.identity.domain.EmailPurpose;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityEmailStore {
    public record Delivery(UUID id, UUID lease, EmailPurpose purpose, String recipient, String linkBase, int attempts) {}

    private final JdbcClient sql;

    IdentityEmailStore(JdbcClient sql) {
        this.sql = sql;
    }

    public boolean take(String bucket, Instant window, int limit) {
        return sql.sql("""
                insert into zonda_market.identity_email_rate as rate (bucket, window_start, hits)
                values (:bucket, :window, 1)
                on conflict (bucket, window_start) do update set hits = rate.hits + 1 where rate.hits < :limit
                """).param("bucket", bucket).param("window", at(window)).param("limit", limit).update() == 1;
    }

    public void enqueue(UUID id, UUID account, EmailPurpose purpose, String hash, String recipient,
                        String linkBase, Instant now) {
        sql.sql("""
                insert into zonda_market.identity_email_token (id, account_id, purpose, token_hash, created_at, expires_at)
                values (:id, :account, :purpose, :hash, :now, :expires)
                """).param("id", id).param("account", account).param("purpose", purpose.name())
                .param("hash", hash).param("now", at(now)).param("expires", at(now.plus(purpose.ttl()))).update();
        sql.sql("""
                insert into zonda_market.identity_mail_outbox (id, recipient, link_base, next_attempt_at)
                values (:id, :recipient, :link, :now)
                """).param("id", id).param("recipient", recipient).param("link", linkBase)
                .param("now", at(now)).update();
    }

    public Optional<UUID> accountFor(EmailPurpose purpose, String hash, Instant now) {
        return sql.sql("""
                select account_id from zonda_market.identity_email_token
                where token_hash = :hash and purpose = :purpose and consumed_at is null and expires_at > :now
                """).param("hash", hash).param("purpose", purpose.name()).param("now", at(now))
                .query(UUID.class).optional();
    }

    public boolean consume(UUID account, EmailPurpose purpose, String hash, Instant now) {
        return sql.sql("""
                update zonda_market.identity_email_token set consumed_at = :now
                where account_id = :account and purpose = :purpose and token_hash = :hash
                    and consumed_at is null and expires_at > :now
                """).param("account", account).param("purpose", purpose.name()).param("hash", hash)
                .param("now", at(now)).update() == 1;
    }

    public void invalidateOthers(UUID account, EmailPurpose purpose, Instant now) {
        sql.sql("""
                update zonda_market.identity_email_token set consumed_at = :now
                where account_id = :account and purpose = :purpose and consumed_at is null
                """).param("account", account).param("purpose", purpose.name()).param("now", at(now)).update();
    }

    public Optional<Delivery> claim(Instant now, Instant until) {
        UUID lease = UUID.randomUUID();
        return sql.sql("""
                with next as (
                    select o.id, t.purpose from zonda_market.identity_mail_outbox o
                    join zonda_market.identity_email_token t on t.id = o.id
                    join zonda_market.user_account a on a.id = t.account_id
                    where o.sent_at is null and o.discarded_at is null and o.next_attempt_at <= :now
                        and (o.lease_until is null or o.lease_until <= :now)
                        and t.consumed_at is null and t.expires_at > :now and not a.deleted
                    order by o.next_attempt_at, o.id limit 1 for update of o skip locked
                )
                update zonda_market.identity_mail_outbox o
                set lease_id = :lease, lease_until = :until, attempts = o.attempts + 1
                from next where o.id = next.id
                returning o.id, next.purpose, o.recipient, o.link_base, o.attempts
                """).param("now", at(now)).param("lease", lease).param("until", at(until))
                .query((rs, row) -> new Delivery(rs.getObject("id", UUID.class), lease,
                        EmailPurpose.valueOf(rs.getString("purpose")), rs.getString("recipient"),
                        rs.getString("link_base"), rs.getInt("attempts"))).optional();
    }

    public void sent(Delivery claim, Instant now, String providerId) {
        sql.sql("""
                update zonda_market.identity_mail_outbox
                set sent_at = :now, provider_id = :provider, lease_id = null, lease_until = null
                where id = :id and lease_id = :lease
                """).param("id", claim.id()).param("lease", claim.lease()).param("now", at(now))
                .param("provider", providerId).update();
    }

    public void retry(Delivery claim, Instant nextAttempt) {
        sql.sql("""
                update zonda_market.identity_mail_outbox
                set next_attempt_at = :next, lease_id = null, lease_until = null
                where id = :id and lease_id = :lease
                """).param("id", claim.id()).param("lease", claim.lease())
                .param("next", at(nextAttempt)).update();
    }

    public void discardStale(Instant now) {
        sql.sql("""
                update zonda_market.identity_mail_outbox o
                set discarded_at = :now, lease_id = null, lease_until = null
                from zonda_market.identity_email_token t join zonda_market.user_account a on a.id = t.account_id
                where o.id = t.id and o.sent_at is null and o.discarded_at is null
                    and (o.lease_until is null or o.lease_until <= :now)
                    and (t.expires_at <= :now or t.consumed_at is not null or a.deleted)
                """).param("now", at(now)).update();
        sql.sql("delete from zonda_market.identity_email_rate where window_start < :cutoff")
                .param("cutoff", at(now.minusSeconds(3600))).update();
    }

    private static OffsetDateTime at(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
