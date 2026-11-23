package com.zondamarket.cart.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CartStore {
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {};
    private static final String OFFER_COLUMNS = """
            SELECT sku.id AS sku_id, sku.price, sku.attributes, sku.available_stock, sku.deleted AS sku_deleted,
                   listing.slug AS listing_slug, listing.title, seller.id AS seller_id, seller.slug AS seller_slug,
                   seller.display_name AS seller_name, seller.account_id AS seller_account_id,
                   public_listing.id IS NOT NULL AS visible, sku.code, category.commission_bps, seller.shipping_cost
            """;
    private static final String OFFER_JOINS = """
            JOIN zonda_market.listing listing ON listing.id = sku.listing_id
            JOIN zonda_market.seller_profile seller ON seller.id = listing.seller_id
            JOIN zonda_market.category category ON category.id = listing.category_id
            LEFT JOIN zonda_market.public_listing public_listing ON public_listing.id = listing.id
            """;

    private final JdbcClient sql;
    private final ObjectMapper json;

    CartStore(JdbcClient sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    public record Cart(UUID id, long version) {}

    public record Offer(UUID skuId, BigDecimal price, Map<String, String> attributes, int stock, boolean deleted,
                        String listingSlug, String title, UUID sellerId, String sellerSlug, String sellerName,
                        UUID sellerAccountId, boolean visible, String code, int commissionBps, BigDecimal shipping) {}

    public record Line(Offer offer, int quantity, BigDecimal referencePrice, String referenceTitle,
                       Map<String, String> referenceAttributes, String thumbnailUrl) {}

    public record ReservedLine(UUID orderId, UUID skuId) {}

    public List<ReservedLine> reserved(UUID cart) {
        return sql.sql("""
                SELECT orders.id AS order_id, reservation.sku_id FROM zonda_market.checkout_order orders
                JOIN zonda_market.stock_reservation reservation ON reservation.order_id = orders.id
                WHERE orders.cart_id = :cart AND orders.status IN ('RESERVADA', 'PAGO_PENDIENTE')
                    AND reservation.status = 'ACTIVA'
                """).param("cart", cart).query(ReservedLine.class).list();
    }

    public Optional<Cart> find(UUID buyer) {
        return sql.sql("SELECT id, version FROM zonda_market.cart WHERE account_id = :buyer")
                .param("buyer", buyer).query(Cart.class).optional();
    }

    public Cart lock(UUID buyer) {
        sql.sql("""
                INSERT INTO zonda_market.cart (id, account_id) VALUES (:id, :buyer)
                ON CONFLICT (account_id) DO NOTHING
                """).param("id", UUID.randomUUID()).param("buyer", buyer).update();
        return sql.sql("SELECT id, version FROM zonda_market.cart WHERE account_id = :buyer FOR UPDATE")
                .param("buyer", buyer).query(Cart.class).single();
    }

    public Optional<Offer> offer(UUID skuId) {
        return sql.sql(OFFER_COLUMNS + " FROM zonda_market.listing_sku sku " + OFFER_JOINS + " WHERE sku.id = :sku")
                .param("sku", skuId).query((rs, row) -> readOffer(rs)).optional();
    }

    public List<Line> lines(UUID cartId) {
        return sql.sql(OFFER_COLUMNS + """
                , item.quantity, item.reference_price, item.reference_title, item.reference_attributes
                , cover.thumbnail_url
                FROM zonda_market.cart_item item
                JOIN zonda_market.listing_sku sku ON sku.id = item.sku_id
                """ + OFFER_JOINS + """
                LEFT JOIN LATERAL (
                    SELECT image.thumbnail_url FROM zonda_market.listing_image image
                    WHERE image.listing_id = listing.id AND NOT image.deleted
                    ORDER BY image.position, image.id LIMIT 1
                ) cover ON public_listing.id IS NOT NULL
                WHERE item.cart_id = :cart AND NOT item.deleted
                ORDER BY seller.display_name, seller.id, item.created_at, item.id
                """).param("cart", cartId)
                .query((rs, row) -> new Line(readOffer(rs), rs.getInt("quantity"), rs.getBigDecimal("reference_price"),
                        rs.getString("reference_title"), json.readValue(rs.getString("reference_attributes"), ATTRIBUTES),
                        rs.getString("thumbnail_url")))
                .list();
    }

    public void put(UUID cartId, Offer offer, int quantity) {
        sql.sql("""
                INSERT INTO zonda_market.cart_item
                    (id, cart_id, sku_id, quantity, reference_price, reference_title, reference_attributes)
                VALUES (:id, :cart, :sku, :quantity, :price, :title, CAST(:attributes AS jsonb))
                ON CONFLICT (cart_id, sku_id) DO UPDATE SET quantity = EXCLUDED.quantity,
                    reference_price = EXCLUDED.reference_price, reference_title = EXCLUDED.reference_title,
                    reference_attributes = EXCLUDED.reference_attributes, deleted = FALSE, updated_at = CURRENT_TIMESTAMP
                """).param("id", UUID.randomUUID()).param("cart", cartId).param("sku", offer.skuId())
                .param("quantity", quantity).param("price", offer.price()).param("title", offer.title())
                .param("attributes", json.writeValueAsString(offer.attributes())).update();
    }

    public boolean remove(UUID cartId, UUID skuId) {
        return sql.sql("""
                UPDATE zonda_market.cart_item SET deleted = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE cart_id = :cart AND sku_id = :sku AND NOT deleted
                """).param("cart", cartId).param("sku", skuId).update() == 1;
    }

    public long advance(UUID cartId) {
        return sql.sql("""
                UPDATE zonda_market.cart SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :cart RETURNING version
                """).param("cart", cartId).query(Long.class).single();
    }

    public int subtractPurchased(UUID cartId, UUID orderId) {
        return sql.sql("""
                UPDATE zonda_market.cart_item item
                SET quantity = CASE WHEN item.quantity = reservation.quantity THEN item.quantity
                                    ELSE item.quantity - reservation.quantity END,
                    deleted = item.quantity = reservation.quantity, updated_at = CURRENT_TIMESTAMP
                FROM zonda_market.stock_reservation reservation
                WHERE reservation.order_id = :order AND reservation.status = 'CONSUMIDA'
                    AND reservation.sku_id = item.sku_id AND item.cart_id = :cart
                    AND NOT item.deleted AND item.quantity >= reservation.quantity
                """).param("cart", cartId).param("order", orderId).update();
    }

    private Offer readOffer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Offer(rs.getObject("sku_id", UUID.class), rs.getBigDecimal("price"),
                json.readValue(rs.getString("attributes"), ATTRIBUTES), rs.getInt("available_stock"),
                rs.getBoolean("sku_deleted"), rs.getString("listing_slug"), rs.getString("title"),
                rs.getObject("seller_id", UUID.class), rs.getString("seller_slug"), rs.getString("seller_name"),
                rs.getObject("seller_account_id", UUID.class), rs.getBoolean("visible"), rs.getString("code"),
                rs.getInt("commission_bps"), rs.getBigDecimal("shipping_cost"));
    }
}
