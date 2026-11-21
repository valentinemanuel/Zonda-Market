package com.zondamarket.checkout.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationExpiryStore {
    private static final String ACTIVE = "status IN ('RESERVADA', 'PAGO_PENDIENTE')";
    private final JdbcClient sql;

    ReservationExpiryStore(JdbcClient sql) {
        this.sql = sql;
    }

    public List<UUID> due(Instant cutoff, int limit) {
        return sql.sql("SELECT id FROM zonda_market.checkout_order WHERE " + ACTIVE + """
                 AND expires_at <= :cutoff ORDER BY expires_at, id LIMIT :limit
                """).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("limit", limit).query(UUID.class).list();
    }

    public List<UUID> dueForBuyer(UUID buyer, Instant cutoff) {
        return sql.sql("SELECT id FROM zonda_market.checkout_order WHERE " + ACTIVE + """
                 AND buyer_id = :buyer AND expires_at <= :cutoff
                """).param("buyer", buyer).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).query(UUID.class).list();
    }

    public List<UUID> dueForSku(UUID sku, Instant cutoff, int limit) {
        return sql.sql("""
                SELECT order_id FROM zonda_market.stock_reservation
                WHERE sku_id = :sku AND status = 'ACTIVA' AND expires_at <= :cutoff
                ORDER BY expires_at, order_id LIMIT :limit
                """).param("sku", sku).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("limit", limit)
                .query(UUID.class).list();
    }

    public List<UUID> dueForCart(UUID buyer, Instant cutoff, int limit) {
        return sql.sql("""
                SELECT DISTINCT reservation.order_id, reservation.expires_at
                FROM zonda_market.cart cart
                JOIN zonda_market.cart_item item ON item.cart_id = cart.id AND NOT item.deleted
                JOIN zonda_market.stock_reservation reservation ON reservation.sku_id = item.sku_id
                WHERE cart.account_id = :buyer AND reservation.status = 'ACTIVA' AND reservation.expires_at <= :cutoff
                ORDER BY reservation.expires_at, reservation.order_id LIMIT :limit
                """).param("buyer", buyer).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("limit", limit)
                .query((rs, row) -> rs.getObject("order_id", UUID.class)).list();
    }

    public boolean lockDue(UUID order, UUID buyer, Instant cutoff, boolean skipLocked) {
        sql.sql("SET LOCAL lock_timeout = '1500ms'").update();
        var query = sql.sql("SELECT id FROM zonda_market.checkout_order WHERE " + ACTIVE
                + " AND id = :id AND expires_at <= :cutoff" + (buyer == null ? "" : " AND buyer_id = :buyer")
                + (skipLocked ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE"))
                .param("id", order).param("cutoff", cutoff.atOffset(ZoneOffset.UTC));
        if (buyer != null) query = query.param("buyer", buyer);
        return query.query(UUID.class).optional().isPresent();
    }

    public void lockResources(UUID order) {
        sql.sql("""
                SELECT id FROM zonda_market.checkout_sub_order WHERE order_id = :order ORDER BY id FOR UPDATE
                """).param("order", order).query(UUID.class).list();
        sql.sql("""
                SELECT sku.id FROM zonda_market.stock_reservation reservation
                JOIN zonda_market.listing_sku sku ON sku.id = reservation.sku_id
                WHERE reservation.order_id = :order AND reservation.status = 'ACTIVA'
                ORDER BY sku.id FOR UPDATE OF sku
                """).param("order", order).query(UUID.class).list();
    }

    public void release(UUID order, UUID trace, Instant now) {
        var timestamp = now.atOffset(ZoneOffset.UTC);
        int released = sql.sql("""
                WITH released AS (
                    UPDATE zonda_market.stock_reservation SET status = 'LIBERADA', updated_at = :now
                    WHERE order_id = :order AND status = 'ACTIVA' RETURNING sku_id, quantity
                ), restored AS (
                    UPDATE zonda_market.listing_sku sku SET available_stock = sku.available_stock + released.quantity,
                        reserved_stock = sku.reserved_stock - released.quantity, updated_at = :now, version = sku.version + 1
                    FROM released WHERE sku.id = released.sku_id RETURNING sku.id
                )
                SELECT count(*) FROM restored
                """).param("order", order).param("now", timestamp).query(Integer.class).single();
        if (released == 0) throw new IllegalStateException("No hay reservas activas para la orden vencida.");
        sql.sql("""
                UPDATE zonda_market.checkout_sub_order SET status = 'CANCELADA', updated_at = :now
                WHERE order_id = :order AND status = 'ESPERA_PAGO'
                """).param("order", order).param("now", timestamp).update();
        sql.sql("""
                UPDATE zonda_market.checkout_order SET status = 'EXPIRADA', updated_at = :now WHERE id = :order
                """).param("order", order).param("now", timestamp).update();
        sql.sql("""
                INSERT INTO zonda_market.checkout_outbox (id, order_id, event_type, trace_id, created_at)
                VALUES (:id, :order, 'CHECKOUT_EXPIRED', :trace, :now)
                """).param("id", UUID.randomUUID()).param("order", order).param("trace", trace).param("now", timestamp).update();
    }
}
