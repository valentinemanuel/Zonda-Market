package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.PaymentWebhookTopic;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentWebhookInbox {
    private final JdbcClient sql;

    PaymentWebhookInbox(JdbcClient sql) {
        this.sql = sql;
    }

    public void receive(PaymentWebhookTopic topic, String event, String resource, String request, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.payment_webhook_inbox
                    (id, topic, notification_id, resource_id, request_id, trace_id, received_at, next_attempt_at)
                VALUES (:id, :topic, :event, :resource, :request, :trace, :now, :now)
                ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("topic", topic.name()).param("event", event)
                .param("resource", resource).param("request", request).param("trace", trace)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Delivery> claim(Instant now) {
        return sql.sql("""
                WITH next AS (
                    SELECT id FROM zonda_market.payment_webhook_inbox
                    WHERE finished_at IS NULL AND next_attempt_at <= :now
                      AND (lease_until IS NULL OR lease_until <= :now)
                    ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE zonda_market.payment_webhook_inbox AS delivery
                SET lease_id = :lease, lease_until = :until, attempts = attempts + 1
                FROM next WHERE delivery.id = next.id
                RETURNING delivery.id, topic, resource_id, trace_id, lease_id, attempts
                """).param("now", now.atOffset(ZoneOffset.UTC)).param("until", now.plusSeconds(30).atOffset(ZoneOffset.UTC))
                .param("lease", UUID.randomUUID()).query(Delivery.class).optional();
    }

    public void finish(Delivery delivery, Instant now, String failure) {
        sql.sql("""
                UPDATE zonda_market.payment_webhook_inbox
                SET finished_at = :now, failure_code = :failure, lease_id = NULL, lease_until = NULL
                WHERE id = :id AND lease_id = :lease
                """).param("now", now.atOffset(ZoneOffset.UTC)).param("failure", failure).param("id", delivery.id())
                .param("lease", delivery.leaseId()).update();
    }

    public void retry(Delivery delivery, Instant next, String failure) {
        sql.sql("""
                UPDATE zonda_market.payment_webhook_inbox
                SET next_attempt_at = :next, failure_code = :failure, lease_id = NULL, lease_until = NULL
                WHERE id = :id AND lease_id = :lease
                """).param("next", next.atOffset(ZoneOffset.UTC)).param("failure", failure).param("id", delivery.id())
                .param("lease", delivery.leaseId()).update();
    }

    public record Delivery(UUID id, PaymentWebhookTopic topic, String resourceId, UUID traceId, UUID leaseId, int attempts) {}
}
