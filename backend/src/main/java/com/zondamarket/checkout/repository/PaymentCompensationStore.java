package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.CompensationDtos.Reason;
import com.zondamarket.checkout.dto.CompensationDtos.Refund;
import com.zondamarket.checkout.dto.CompensationDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentCompensationStore {
    private final JdbcClient sql;

    PaymentCompensationStore(JdbcClient sql) { this.sql = sql; }

    public void enqueue(UUID order, Method method, String payment, Reason reason, BigDecimal amount, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.payment_compensation
                    (id, order_id, method, provider_payment_id, reason, amount, trace_id, created_at, updated_at, next_attempt_at)
                VALUES (:id, :order, :method, :payment, :reason, :amount, :trace, :now, :now, :now)
                ON CONFLICT (method, provider_payment_id) DO NOTHING
                """).param("id", UUID.randomUUID()).param("order", order).param("method", method.name())
                .param("payment", payment).param("reason", reason.name()).param("amount", amount)
                .param("trace", trace).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public List<Refund> forOrder(UUID order) {
        return sql.sql("""
                SELECT id, reason, status, amount::text AS amount
                FROM zonda_market.payment_compensation
                WHERE order_id = :order ORDER BY created_at, id
                """).param("order", order).query(Refund.class).list();
    }

    public Optional<Work> claim(Set<Method> methods, Instant now) {
        return sql.sql("""
                WITH due AS (
                    SELECT id FROM zonda_market.payment_compensation
                    WHERE (status = 'PENDIENTE' OR reconcile_only) AND method IN (:methods) AND next_attempt_at <= :now
                      AND (lease_until IS NULL OR lease_until <= :now)
                    ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE zonda_market.payment_compensation AS refund
                SET lease_id = :lease, lease_until = :until, attempts = refund.attempts + 1
                FROM due, zonda_market.payment_transaction payment
                JOIN zonda_market.payment_attempt attempt ON attempt.id = payment.attempt_id
                WHERE refund.id = due.id AND payment.method = refund.method
                  AND payment.provider_payment_id = refund.provider_payment_id
                RETURNING refund.id, refund.method, payment.provider_order_id, refund.provider_payment_id,
                          attempt.external_reference, refund.amount, payment.currency, refund.provider_refund_id,
                          refund.lease_id, refund.trace_id, refund.attempts, refund.created_at, refund.reconcile_only
                """).param("methods", methods.stream().map(Method::name).toList())
                .param("lease", UUID.randomUUID()).param("now", now.atOffset(ZoneOffset.UTC))
                .param("until", now.plusSeconds(120).atOffset(ZoneOffset.UTC)).query(Work.class).optional();
    }

    public void save(Work work, Status status, String providerId, String failure, Instant now, Instant next) {
        sql.sql("""
                UPDATE zonda_market.payment_compensation
                SET status = :status, provider_refund_id = COALESCE(:providerId, provider_refund_id), failure_code = :failure,
                    updated_at = :now, next_attempt_at = :next, lease_id = NULL, lease_until = NULL, reconcile_only = FALSE
                WHERE id = :id AND lease_id = :lease
                """).param("status", status.name()).param("providerId", providerId).param("failure", failure)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("next", next.atOffset(ZoneOffset.UTC))
                .param("id", work.id()).param("lease", work.leaseId()).update();
    }

    public record Work(UUID id, Method method, String providerOrderId, String providerPaymentId, String externalReference,
                       BigDecimal amount, String currency, String providerRefundId, UUID leaseId, UUID traceId,
                       int attempts, Instant createdAt, boolean reconcileOnly) {}
}
