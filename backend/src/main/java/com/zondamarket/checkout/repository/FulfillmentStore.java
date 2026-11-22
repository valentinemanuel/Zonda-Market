package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.FulfillmentDtos.Carrier;
import com.zondamarket.checkout.dto.FulfillmentDtos.ShipmentResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FulfillmentStore {
    private final JdbcClient sql;

    FulfillmentStore(JdbcClient sql) {
        this.sql = sql;
    }

    public record ParentOrder(UUID id, String status) {}

    public Optional<ParentOrder> lockParent(UUID subOrder, UUID owner, boolean seller) {
        String ownership = seller ? "sub.seller_id" : "purchase.buyer_id";
        return sql.sql("""
                SELECT purchase.id, purchase.status
                FROM zonda_market.checkout_order purchase
                JOIN zonda_market.checkout_sub_order sub ON sub.order_id = purchase.id
                WHERE sub.id = :sub AND %s = :owner
                FOR UPDATE OF purchase
                """.formatted(ownership)).param("sub", subOrder).param("owner", owner)
                .query(ParentOrder.class).optional();
    }

    public ShipmentResponse lockSubOrder(UUID id) {
        return sql.sql("""
                SELECT status, fulfillment_version, carrier, tracking_code, shipped_at, delivered_at
                FROM zonda_market.checkout_sub_order WHERE id = :id FOR UPDATE
                """).param("id", id).query((row, i) -> new ShipmentResponse(id,
                    row.getString("status"), row.getInt("fulfillment_version"), CheckoutStore.readShipment(row))).single();
    }

    public void ship(UUID id, Carrier carrier, String tracking, Instant now) {
        sql.sql("""
                UPDATE zonda_market.checkout_sub_order
                SET status = 'DESPACHADA', carrier = :carrier, tracking_code = :tracking,
                    shipped_at = :now, updated_at = :now, fulfillment_version = fulfillment_version + 1
                WHERE id = :id
                """).param("id", id).param("carrier", carrier.name()).param("tracking", tracking)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public void deliver(UUID id, Instant now) {
        sql.sql("""
                UPDATE zonda_market.checkout_sub_order
                SET status = 'ENTREGADA', delivered_at = :now, updated_at = :now,
                    fulfillment_version = fulfillment_version + 1
                WHERE id = :id
                """).param("id", id).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public void event(UUID order, UUID subOrder, String type, UUID actor, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_outbox
                    (id, order_id, sub_order_id, event_type, actor_id, trace_id, created_at)
                VALUES (:id, :order, :sub, :type, :actor, :trace, :now)
                """).param("id", UUID.randomUUID()).param("order", order).param("sub", subOrder).param("type", type)
                .param("actor", actor).param("trace", trace).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }
}
