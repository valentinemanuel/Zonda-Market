package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentTransactionStore {
    private final JdbcClient sql;

    PaymentTransactionStore(JdbcClient sql) {
        this.sql = sql;
    }

    public boolean record(Method method, String paymentId, String orderId, UUID attemptId, Status status,
                          BigDecimal amount, String currency, UUID trace, Instant now) {
        int updated = sql.sql("""
                INSERT INTO zonda_market.payment_transaction AS saved
                    (method, provider_payment_id, provider_order_id, attempt_id, status, amount, currency, trace_id, verified_at)
                VALUES (:method, :payment, :order, :attempt, :status, :amount, :currency, :trace, :now)
                ON CONFLICT (method, provider_payment_id) DO UPDATE
                SET status = excluded.status, trace_id = excluded.trace_id, verified_at = excluded.verified_at
                WHERE saved.attempt_id = excluded.attempt_id AND saved.provider_order_id = excluded.provider_order_id
                  AND saved.status <> excluded.status AND saved.status <> 'APROBADO' AND excluded.status <> 'PENDIENTE'
                """).param("method", method.name()).param("payment", paymentId).param("order", orderId)
                .param("attempt", attemptId).param("status", status.name()).param("amount", amount).param("currency", currency)
                .param("trace", trace).param("now", now.atOffset(ZoneOffset.UTC)).update();
        return updated > 0 || sql.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM zonda_market.payment_transaction
                    WHERE method = :method AND provider_payment_id = :payment
                      AND provider_order_id = :order AND attempt_id = :attempt
                )
                """).param("method", method.name()).param("payment", paymentId).param("order", orderId)
                .param("attempt", attemptId).query(Boolean.class).single();
    }
}
