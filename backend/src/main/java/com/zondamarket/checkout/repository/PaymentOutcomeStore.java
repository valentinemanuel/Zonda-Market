package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.service.LocalPaymentPort.Outcome;
import com.zondamarket.checkout.service.LocalPaymentPort.VerifiedOutcome;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOutcomeStore {
    private final JdbcClient sql;

    PaymentOutcomeStore(JdbcClient sql) {
        this.sql = sql;
    }

    public enum Result { APPROVED, REJECTED, LATE_APPROVAL, EXPIRED }

    public record Recorded(String reference, Outcome outcome, Result result, Method paymentMethod, String providerPaymentId) {}

    public Optional<Recorded> find(UUID id) {
        return sql.sql("""
                SELECT reference, outcome, result, payment_method, provider_payment_id
                FROM zonda_market.checkout_payment_outcome WHERE order_id = :id
                """).param("id", id).query(Recorded.class).optional();
    }

    public void insert(UUID order, VerifiedOutcome outcome, Result result, Method method, String paymentId, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_payment_outcome
                    (order_id, reference, outcome, result, amount, currency, trace_id, created_at, payment_method, provider_payment_id)
                VALUES (:order, :reference, :outcome, :result, :amount, :currency, :trace, :now, :method, :payment)
                """).param("order", order).param("reference", outcome.reference()).param("outcome", outcome.outcome().name())
                .param("result", result.name()).param("amount", outcome.amount()).param("currency", outcome.currency())
                .param("trace", trace).param("now", now.atOffset(ZoneOffset.UTC))
                .param("method", method == null ? null : method.name()).param("payment", paymentId).update();
    }
}
