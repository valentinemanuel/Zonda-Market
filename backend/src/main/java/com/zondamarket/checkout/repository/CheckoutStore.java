package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.CheckoutDtos.Group;
import com.zondamarket.checkout.dto.CheckoutDtos.Line;
import com.zondamarket.checkout.dto.CheckoutDtos.OrderSummary;
import com.zondamarket.checkout.dto.CheckoutDtos.Snapshot;
import com.zondamarket.checkout.dto.CheckoutDtos.SubOrder;
import com.zondamarket.identity.dto.ProfileDtos.AddressResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CheckoutStore {
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {};
    private static final String ORDER_COLUMNS = """
            SELECT id, status, total, currency, expires_at, request_hash FROM zonda_market.checkout_order
            """;
    private final JdbcClient sql;
    private final ObjectMapper json;

    CheckoutStore(JdbcClient sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    public record Quote(UUID id, long cartVersion, Instant expiresAt, Snapshot snapshot) {}

    public record Order(UUID id, String status, BigDecimal total, String currency, Instant expiresAt, String requestHash) {}

    public Optional<AddressResponse> address(UUID buyer, UUID id, boolean lock) {
        return sql.sql("""
                SELECT id, street, city, province_code, postal_code, country FROM zonda_market.account_address
                WHERE id = :id AND account_id = :buyer AND NOT deleted
                """ + (lock ? " FOR SHARE" : ""))
                .param("id", id).param("buyer", buyer).query(AddressResponse.class).optional();
    }

    public void saveQuote(UUID id, UUID cart, long version, Snapshot snapshot, Instant now, Instant expiry) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_quote (id, cart_id, cart_version, address_id, snapshot, created_at, expires_at)
                VALUES (:id, :cart, :version, :address, CAST(:snapshot AS jsonb), :now, :expiry)
                """).param("id", id).param("cart", cart).param("version", version).param("address", snapshot.address().id())
                .param("snapshot", json.writeValueAsString(snapshot)).param("now", utc(now)).param("expiry", utc(expiry)).update();
    }

    public Optional<Quote> quote(UUID cart, UUID id) {
        return sql.sql("""
                SELECT id, cart_version, expires_at, snapshot FROM zonda_market.checkout_quote
                WHERE id = :id AND cart_id = :cart
                """).param("id", id).param("cart", cart).query((rs, row) ->
                    new Quote(id, rs.getLong("cart_version"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            json.readValue(rs.getString("snapshot"), Snapshot.class))).optional();
    }

    public Optional<Order> find(UUID buyer, UUID id) {
        return sql.sql(ORDER_COLUMNS + " WHERE id = :id AND buyer_id = :buyer")
                .param("id", id).param("buyer", buyer).query((rs, row) -> readOrder(rs)).optional();
    }

    public Optional<Order> lock(UUID buyer, UUID id) {
        return sql.sql(ORDER_COLUMNS + " WHERE id = :id AND buyer_id = :buyer FOR UPDATE")
                .param("id", id).param("buyer", buyer).query((rs, row) -> readOrder(rs)).optional();
    }

    public Optional<Order> active(UUID buyer) {
        return sql.sql(ORDER_COLUMNS + " WHERE buyer_id = :buyer AND status IN ('RESERVADA', 'PAGO_PENDIENTE')")
                .param("buyer", buyer).query((rs, row) -> readOrder(rs)).optional();
    }

    public Optional<String> confirmedStatus(UUID buyer, UUID quote) {
        return sql.sql("SELECT status FROM zonda_market.checkout_order WHERE buyer_id = :buyer AND quote_id = :quote")
                .param("buyer", buyer).param("quote", quote).query(String.class).optional();
    }

    public List<OrderSummary> history(UUID buyer, int page, int size) {
        return sql.sql("""
                SELECT id, created_at, expires_at, status, total, currency FROM zonda_market.checkout_order
                WHERE buyer_id = :buyer ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit
                """).param("buyer", buyer).param("offset", (long) page * size).param("limit", size + 1)
                .query((rs, row) -> new OrderSummary(rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getString("status"), rs.getBigDecimal("total").toPlainString(), rs.getString("currency")))
                .list();
    }

    public Optional<Order> replay(UUID buyer, String key) {
        return sql.sql(ORDER_COLUMNS + " WHERE buyer_id = :buyer AND idempotency_key = :key")
                .param("buyer", buyer).param("key", key).query((rs, row) -> readOrder(rs)).optional();
    }

    public void lockSkus(UUID cart) {
        sql.sql("""
                SELECT sku.id FROM zonda_market.cart_item item
                JOIN zonda_market.listing_sku sku ON sku.id = item.sku_id
                WHERE item.cart_id = :cart AND NOT item.deleted
                ORDER BY sku.id FOR UPDATE OF sku
                """).param("cart", cart).query(UUID.class).list();
    }

    public void insertOrder(Order order, UUID buyer, UUID cart, UUID quote, String key, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_order
                    (id, buyer_id, cart_id, quote_id, status, total, currency, idempotency_key, request_hash,
                     trace_id, created_at, updated_at, expires_at)
                VALUES (:id, :buyer, :cart, :quote, :status, :total, :currency, :key, :hash, :trace, :now, :now, :expiry)
                """).param("id", order.id()).param("buyer", buyer).param("cart", cart).param("quote", quote)
                .param("status", order.status()).param("total", order.total()).param("currency", order.currency())
                .param("key", key).param("hash", order.requestHash()).param("trace", trace).param("now", utc(now))
                .param("expiry", utc(order.expiresAt())).update();
    }

    public void insertSubOrder(UUID order, SubOrder sub, Instant now) {
        Group group = sub.group();
        sql.sql("""
                INSERT INTO zonda_market.checkout_sub_order
                    (id, order_id, seller_id, seller_name, status, address_snapshot, merchandise, shipping, commission,
                     created_at, updated_at)
                VALUES (:id, :order, :seller, :name, :status, CAST(:address AS jsonb), :merchandise, :shipping, :commission, :now, :now)
                """).param("id", sub.id()).param("order", order).param("seller", group.sellerId()).param("name", group.sellerName())
                .param("status", sub.status()).param("address", json.writeValueAsString(sub.address()))
                .param("merchandise", new BigDecimal(group.merchandise())).param("shipping", new BigDecimal(group.shipping()))
                .param("commission", new BigDecimal(group.commission())).param("now", utc(now)).update();
    }

    public void insertLine(UUID subOrder, Line line, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_order_line
                    (id, sub_order_id, sku_id, sku_code, title, attributes, quantity, unit_price, commission_bps, commission, created_at)
                VALUES (:id, :sub, :sku, :code, :title, CAST(:attributes AS jsonb), :quantity, :price, :bps, :commission, :now)
                """).param("id", UUID.randomUUID()).param("sub", subOrder).param("sku", line.skuId())
                .param("code", line.skuCode()).param("title", line.title()).param("attributes", json.writeValueAsString(line.attributes()))
                .param("quantity", line.quantity()).param("price", new BigDecimal(line.unitPrice()))
                .param("bps", line.commissionBps()).param("commission", new BigDecimal(line.commission())).param("now", utc(now)).update();
    }

    public boolean reserve(Order order, Line line, Instant now) {
        int changed = sql.sql("""
                UPDATE zonda_market.listing_sku SET available_stock = available_stock - :quantity,
                    reserved_stock = reserved_stock + :quantity, updated_at = :now, version = version + 1
                WHERE id = :sku AND available_stock >= :quantity AND NOT deleted
                    AND reserved_stock <= 2147483647 - :quantity
                """).param("sku", line.skuId()).param("quantity", line.quantity()).param("now", utc(now)).update();
        if (changed != 1) return false;
        sql.sql("""
                INSERT INTO zonda_market.stock_reservation
                    (id, order_id, sku_id, quantity, status, created_at, updated_at, expires_at)
                VALUES (:id, :order, :sku, :quantity, 'ACTIVA', :now, :now, :expiry)
                """).param("id", UUID.randomUUID()).param("order", order.id()).param("sku", line.skuId())
                .param("quantity", line.quantity()).param("now", utc(now)).param("expiry", utc(order.expiresAt())).update();
        return true;
    }

    public void recordReserved(UUID order, UUID trace, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.checkout_outbox (id, order_id, event_type, trace_id, created_at)
                VALUES (:id, :order, 'CHECKOUT_RESERVED', :trace, :now)
                """).param("id", UUID.randomUUID()).param("order", order).param("trace", trace).param("now", utc(now)).update();
    }

    public List<SubOrder> subOrders(UUID order) {
        var lines = sql.sql("""
                SELECT sub.id AS sub_id, sub.status, sub.address_snapshot, sub.seller_id, sub.seller_name,
                       sub.merchandise, sub.shipping, sub.commission AS sub_commission, line.sku_id, line.sku_code,
                       line.title, line.attributes, line.quantity, line.unit_price, line.commission_bps, line.commission
                FROM zonda_market.checkout_sub_order sub
                JOIN zonda_market.checkout_order_line line ON line.sub_order_id = sub.id
                WHERE sub.order_id = :order
                ORDER BY sub.seller_name, sub.seller_id, line.sku_id
                """).param("order", order).query((rs, row) -> {
                    var items = new ArrayList<Line>();
                    var merchandise = rs.getBigDecimal("merchandise");
                    var shipping = rs.getBigDecimal("shipping");
                    var group = new Group(rs.getObject("seller_id", UUID.class), rs.getString("seller_name"), items,
                            merchandise.toPlainString(), shipping.toPlainString(), rs.getBigDecimal("sub_commission").toPlainString(),
                            merchandise.add(shipping).toPlainString());
                    var sub = new SubOrder(rs.getObject("sub_id", UUID.class), rs.getString("status"),
                            json.readValue(rs.getString("address_snapshot"), AddressResponse.class), group);
                    int quantity = rs.getInt("quantity");
                    var price = rs.getBigDecimal("unit_price");
                    var line = new Line(rs.getObject("sku_id", UUID.class), rs.getString("sku_code"), rs.getString("title"),
                            json.readValue(rs.getString("attributes"), ATTRIBUTES), quantity, price.toPlainString(),
                            price.multiply(BigDecimal.valueOf(quantity)).toPlainString(), rs.getInt("commission_bps"),
                            rs.getBigDecimal("commission").toPlainString());
                    return new FrozenLine(sub, line);
                }).list();
        Map<UUID, SubOrder> groups = new LinkedHashMap<>();
        for (var row : lines) {
            groups.computeIfAbsent(row.sub().id(), ignored -> row.sub()).group().items().add(row.line());
        }
        return List.copyOf(groups.values());
    }

    private static Order readOrder(ResultSet rs) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getString("status"), rs.getBigDecimal("total"), rs.getString("currency"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("request_hash"));
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record FrozenLine(SubOrder sub, Line line) {}
}
