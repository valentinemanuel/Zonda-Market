package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.DisputeResolutionDtos.*;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DisputeResolutionStore {
    private static final String RESULT = """
            id, outcome, status, amount::text AS amount, reason, provider_refund_id,
            (outcome = 'REEMBOLSO' AND method IS NULL) AS simulated, updated_at
            """;
    private final JdbcClient sql;

    DisputeResolutionStore(JdbcClient sql) { this.sql = sql; }

    public record Context(UUID orderId, UUID subOrderId, UUID disputeId) {}
    public record Order(String status, String currency, BigDecimal total) {}
    public record Capture(Method method, String paymentId, String providerOrderId, String reference, BigDecimal amount,
                          boolean simulated) {}
    public record Sale(BigDecimal merchandise, BigDecimal shipping, BigDecimal commission) {}
    public record Totals(BigDecimal paymentHeld, BigDecimal subHeld, BigDecimal subConfirmed,
                         BigDecimal merchandiseConfirmed, BigDecimal commissionReversed, BigDecimal paymentConfirmed) {}
    public record Work(UUID id, UUID disputeId, UUID orderId, UUID subOrderId, UUID actorId, UUID traceId, UUID leaseId,
                       Method method, String paymentId, String providerOrderId, String paymentReference, BigDecimal amount,
                       BigDecimal captured, String providerRefundId, OffsetDateTime sentAt, int attempts, Status status) {}
    public record Replay(UUID actorId, int expectedVersion, UUID id) {}

    public Optional<Context> context(UUID dispute) {
        return sql.sql("""
                SELECT sub.order_id, sub.id AS sub_order_id, d.id AS dispute_id FROM zonda_market.dispute d
                JOIN zonda_market.checkout_sub_order sub ON sub.id = d.sub_order_id WHERE d.id = :id
                """).param("id", dispute).query(Context.class).optional();
    }

    public Order lockOrder(UUID order) {
        return sql.sql("SELECT status, currency, total FROM zonda_market.checkout_order WHERE id = :id FOR UPDATE")
                .param("id", order).query(Order.class).single();
    }

    public Optional<Capture> capture(UUID order) {
        return sql.sql("""
                SELECT payment.method, payment.provider_payment_id AS payment_id, payment.provider_order_id,
                       result.reference, result.amount,
                       (result.provider_payment_id IS NULL AND
                        (result.reference = concat('local-', result.order_id) OR EXISTS(
                           SELECT FROM zonda_market.payment_attempt a WHERE a.order_id = result.order_id
                               AND a.external_reference = result.reference AND a.provider = 'LOCAL'))) AS simulated
                FROM zonda_market.checkout_payment_outcome result
                LEFT JOIN LATERAL (
                    SELECT candidate.method, candidate.provider_payment_id, candidate.provider_order_id
                    FROM (
                        SELECT tx.*, count(*) OVER () AS candidates
                        FROM zonda_market.payment_transaction tx
                        JOIN zonda_market.payment_attempt a ON a.id = tx.attempt_id
                        WHERE tx.status = 'APROBADO' AND tx.amount = result.amount AND tx.currency = result.currency
                          AND a.order_id = result.order_id AND a.external_reference = result.reference
                          AND ((tx.method, tx.provider_payment_id) = (result.payment_method, result.provider_payment_id)
                             OR (result.provider_payment_id IS NULL AND a.provider = 'MERCADO_PAGO'
                                 AND a.provider_order_id = tx.provider_order_id))
                    ) candidate WHERE candidate.candidates = 1
                ) payment ON TRUE
                WHERE result.order_id = :id AND result.result = 'APPROVED'
                  AND NOT EXISTS (SELECT FROM zonda_market.payment_compensation c
                    WHERE c.method = payment.method AND c.provider_payment_id = payment.provider_payment_id)
                """).param("id", order).query(Capture.class).optional();
    }

    public Optional<Sale> sale(UUID sub) {
        return sql.sql("""
                SELECT merchandise, shipping, commission FROM zonda_market.checkout_sub_order_settlement WHERE sub_order_id = :sub
                """).param("sub", sub).query(Sale.class).optional();
    }

    public Totals totals(Context ctx) {
        return sql.sql("""
                WITH held AS (
                    SELECT sub_order_id, amount FROM zonda_market.dispute_resolution
                    WHERE order_id = :order AND status IN ('PENDIENTE', 'EN_REVISION')
                ), confirmed AS (
                    SELECT r.* FROM zonda_market.checkout_sub_order_refund r
                    JOIN zonda_market.checkout_sub_order sub ON sub.id = r.sub_order_id
                    WHERE sub.order_id = :order
                )
                SELECT COALESCE((SELECT sum(amount) FROM held), 0) AS payment_held,
                       COALESCE((SELECT sum(amount) FROM held WHERE sub_order_id = :sub), 0) AS sub_held,
                       COALESCE(sum(merchandise_refunded + shipping_refunded) FILTER (WHERE sub_order_id = :sub), 0) AS sub_confirmed,
                       COALESCE(sum(merchandise_refunded) FILTER (WHERE sub_order_id = :sub), 0) AS merchandise_confirmed,
                       COALESCE(sum(commission_reversed) FILTER (WHERE sub_order_id = :sub), 0) AS commission_reversed,
                       COALESCE(sum(merchandise_refunded + shipping_refunded), 0) AS payment_confirmed
                FROM confirmed
                """).param("order", ctx.orderId()).param("sub", ctx.subOrderId()).query(Totals.class).single();
    }

    public Optional<Replay> replay(UUID dispute, UUID key) {
        return sql.sql("""
                SELECT actor_id, expected_version, id FROM zonda_market.dispute_resolution
                WHERE dispute_id = :dispute AND request_key = :key
                """).param("dispute", dispute).param("key", key).query(Replay.class).optional();
    }

    public Resolution result(UUID id) {
        return sql.sql("SELECT " + RESULT + " FROM zonda_market.dispute_resolution WHERE id = :id")
                .param("id", id).query(Resolution.class).single();
    }

    public Optional<Resolution> latest(UUID dispute) {
        return sql.sql("SELECT " + RESULT + """
                 FROM zonda_market.dispute_resolution WHERE dispute_id = :id ORDER BY sequence DESC LIMIT 1
                """).param("id", dispute).query(Resolution.class).optional();
    }

    public void insert(UUID id, Context ctx, UUID actor, UUID key, ResolveRequest request, String reason,
                       Capture capture, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.dispute_resolution (id, dispute_id, order_id, sub_order_id, actor_id,
                    request_key, expected_version, reason, outcome, status, amount, method, payment_id, provider_order_id,
                    payment_reference, captured, trace_id, created_at, updated_at, next_attempt_at)
                VALUES (:id, :dispute, :order, :sub, :actor, :key, :version, :reason, :outcome, :status, :amount,
                    :method, :payment, :providerOrder, :reference, :captured, :trace, :now, :now, :now)
                """).param("id", id).param("dispute", ctx.disputeId()).param("order", ctx.orderId()).param("sub", ctx.subOrderId())
                .param("actor", actor).param("key", key).param("version", request.expectedVersion().intValueExact())
                .param("reason", reason).param("outcome", request.outcome().name())
                .param("status", capture == null ? "CERRADA" : "PENDIENTE").param("amount", request.amount())
                .param("method", capture == null || capture.method() == null ? null : capture.method().name())
                .param("payment", capture == null ? null : capture.paymentId())
                .param("providerOrder", capture == null ? null : capture.providerOrderId())
                .param("reference", capture == null ? null : capture.reference())
                .param("captured", capture == null ? null : capture.amount())
                .param("trace", trace).param("now", at(now)).update();
    }

    public Optional<Work> claim(Instant now) {
        return sql.sql("""
                WITH due AS (
                    SELECT r.id FROM zonda_market.dispute_resolution r
                    WHERE r.status IN ('PENDIENTE', 'EN_REVISION') AND r.next_attempt_at <= :now
                      AND (r.lease_until IS NULL OR r.lease_until <= :now)
                      AND NOT EXISTS (SELECT FROM zonda_market.dispute_resolution older
                        WHERE older.order_id = r.order_id AND older.sequence < r.sequence
                          AND older.status IN ('PENDIENTE', 'EN_REVISION'))
                    ORDER BY r.next_attempt_at, r.sequence LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                )
                UPDATE zonda_market.dispute_resolution r
                SET lease_id = :lease, lease_until = :until, attempts = r.attempts + 1
                FROM due WHERE due.id = r.id
                RETURNING r.id, r.dispute_id, r.order_id, r.sub_order_id, r.actor_id, r.trace_id, r.lease_id,
                          r.method, r.payment_id, r.provider_order_id, r.payment_reference, r.amount, r.captured,
                          r.provider_refund_id, r.sent_at, r.attempts, r.status
                """).param("lease", UUID.randomUUID()).param("now", at(now))
                .param("until", at(now.plusSeconds(120))).query(Work.class).optional();
    }

    public List<String> knownReferences(Work work) {
        return sql.sql("""
                SELECT provider_refund_id FROM zonda_market.dispute_resolution WHERE order_id = :order AND id <> :id
                    AND status IN ('CONFIRMADA', 'RECHAZADA') AND provider_refund_id IS NOT NULL
                """).param("order", work.orderId()).param("id", work.id()).query(String.class).list();
    }

    public boolean markSent(Work work, Instant now) {
        return sql.sql("""
                UPDATE zonda_market.dispute_resolution SET sent_at = COALESCE(sent_at, :now)
                WHERE id = :id AND lease_id = :lease AND lease_until > :now
                """).param("now", at(now)).param("id", work.id()).param("lease", work.leaseId()).update() == 1;
    }

    public boolean save(Work work, Status status, String reference, Instant now, Instant next) {
        return sql.sql("""
                UPDATE zonda_market.dispute_resolution SET status = :status, updated_at = :now, next_attempt_at = :next,
                    provider_refund_id = COALESCE(:reference, provider_refund_id), lease_id = NULL, lease_until = NULL
                WHERE id = :id AND lease_id = :lease
                """).param("status", status.name()).param("now", at(now)).param("next", at(next))
                .param("reference", reference).param("id", work.id()).param("lease", work.leaseId()).update() == 1;
    }

    public void adjustment(Work work, BigDecimal merchandise, BigDecimal shipping, BigDecimal commission, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_sub_order_refund
                    (refund_id, sub_order_id, merchandise_refunded, shipping_refunded, commission_reversed, confirmed_at, trace_id)
                VALUES (:id, :sub, :merchandise, :shipping, :commission, :now, :trace)
                """).param("id", work.id()).param("sub", work.subOrderId()).param("merchandise", merchandise)
                .param("shipping", shipping).param("commission", commission).param("now", at(now))
                .param("trace", work.traceId()).update();
    }

    public void refunded(Work work, boolean subTotal, boolean orderTotal, Instant now) {
        sql.sql("""
                UPDATE zonda_market.checkout_order SET status = :status, updated_at = :now WHERE id = :id
                """).param("status", orderTotal ? "REEMBOLSADA" : "REEMBOLSO_PARCIAL")
                .param("now", at(now)).param("id", work.orderId()).update();
        if (subTotal) {
            sql.sql("""
                    UPDATE zonda_market.checkout_sub_order SET status = 'REEMBOLSADA', updated_at = :now,
                        fulfillment_version = fulfillment_version + 1 WHERE id = :id
                    """).param("now", at(now)).param("id", work.subOrderId()).update();
        }
    }

    private static OffsetDateTime at(Instant now) { return now.atOffset(ZoneOffset.UTC); }
}
