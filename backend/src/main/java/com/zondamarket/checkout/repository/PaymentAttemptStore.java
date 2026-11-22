package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Provider;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentAttemptStore {
    private static final String SELECT = """
            SELECT id, method, provider, status, provider_key, external_reference, amount, currency,
                   expires_at, card_request_key, provider_payment_id
            FROM zonda_market.payment_attempt WHERE order_id = :order
            """;
    private final JdbcClient sql;

    PaymentAttemptStore(JdbcClient sql) {
        this.sql = sql;
    }

    public record Attempt(UUID id, Method method, Provider provider, Status status, UUID providerKey,
                          String externalReference, BigDecimal amount, String currency, Instant expiresAt,
                          String cardRequestKey, Long providerPaymentId) {}

    public Optional<Attempt> find(UUID order, UUID id) {
        return sql.sql(SELECT + " AND id = :id").param("order", order).param("id", id)
                .query(Attempt.class).optional();
    }

    public Optional<Attempt> byKey(UUID order, String key) {
        return sql.sql(SELECT + " AND request_key = :key").param("order", order).param("key", key)
                .query(Attempt.class).optional();
    }

    public boolean cardKeyInUse(UUID order, String key) {
        return sql.sql("""
                SELECT EXISTS (SELECT 1 FROM zonda_market.payment_attempt
                    WHERE order_id = :order AND card_request_key = :key)
                """).param("order", order).param("key", key).query(Boolean.class).single();
    }

    public Optional<Attempt> latest(UUID order) {
        return sql.sql(SELECT + """
                 ORDER BY sequence DESC LIMIT 1
                """).param("order", order).query(Attempt.class).optional();
    }

    public void insert(UUID order, Attempt attempt, String key, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.payment_attempt
                    (id, order_id, method, provider, status, request_key, provider_key, external_reference,
                     amount, currency, expires_at, trace_id, created_at, updated_at)
                VALUES (:id, :order, :method, :provider, 'CREADO', :key, :providerKey, :reference,
                        :amount, :currency, :expiresAt, :trace, :now, :now)
                """).param("id", attempt.id()).param("order", order).param("method", attempt.method().name())
                .param("provider", attempt.provider().name()).param("key", key).param("providerKey", attempt.providerKey())
                .param("reference", attempt.externalReference()).param("amount", attempt.amount())
                .param("currency", attempt.currency()).param("expiresAt", attempt.expiresAt().atOffset(ZoneOffset.UTC))
                .param("trace", trace).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public void claimCard(UUID order, UUID id, String key, UUID trace, Instant now) {
        sql.sql("""
                UPDATE zonda_market.payment_attempt
                SET status = 'ENVIADO', card_request_key = :key, trace_id = :trace, updated_at = :now
                WHERE order_id = :order AND id = :id
                """).param("order", order).param("id", id).param("key", key).param("trace", trace)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public void bindProviderPayment(UUID order, UUID id, long providerId, Instant now) {
        sql.sql("""
                UPDATE zonda_market.payment_attempt SET provider_payment_id = :payment, updated_at = :now
                WHERE order_id = :order AND id = :id AND provider_payment_id IS NULL
                """).param("order", order).param("id", id).param("payment", providerId)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public void update(UUID order, UUID id, Status status, UUID trace, Instant now) {
        sql.sql("""
                UPDATE zonda_market.payment_attempt SET status = :status, trace_id = :trace, updated_at = :now
                WHERE order_id = :order AND id = :id
                """).param("order", order).param("id", id).param("status", status.name())
                .param("trace", trace).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }
}
