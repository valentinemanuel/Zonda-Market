package com.zondamarket.notification.repository;

import com.zondamarket.notification.dto.NotificationDtos.NotificationResponse;
import com.zondamarket.notification.dto.NotificationDtos.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationStore {
    private final JdbcClient sql;

    NotificationStore(JdbcClient sql) {
        this.sql = sql;
    }

    public record Event(UUID id, UUID orderId, UUID subOrderId, String eventType, Instant createdAt) {}
    public record Recipient(UUID id, String verifiedEmail, UUID subOrderId) {}
    public record Delivery(UUID id, UUID lease, Type type, String email, String href, int attempts) {}

    public Optional<Event> claimEvent() {
        return sql.sql("""
                SELECT id, order_id, sub_order_id, event_type, created_at FROM zonda_market.checkout_outbox
                WHERE notification_pending AND event_type IN
                    ('CHECKOUT_PAID', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED', 'DISPUTE_OPENED', 'DISPUTE_ASSIGNED', 'DISPUTE_RESOLVED')
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query((row, i) -> new Event(row.getObject("id", UUID.class),
                        row.getObject("order_id", UUID.class), row.getObject("sub_order_id", UUID.class),
                        row.getString("event_type"), row.getObject("created_at", OffsetDateTime.class).toInstant())).optional();
    }

    public Optional<Recipient> buyer(UUID orderId) {
        return sql.sql("""
                SELECT account.id, CASE WHEN account.email_verified THEN account.email END AS verified_email,
                       NULL::UUID AS sub_order_id
                FROM zonda_market.checkout_order purchase
                JOIN zonda_market.user_account account ON account.id = purchase.buyer_id
                WHERE purchase.id = :order AND NOT account.deleted
                """).param("order", orderId).query(NotificationStore::recipient).optional();
    }

    public List<Recipient> sellers(Event event) {
        String filter = event.subOrderId() == null ? "" : " AND sub.id = :sub";
        var query = sql.sql("""
                SELECT account.id, CASE WHEN account.email_verified THEN account.email END AS verified_email,
                       sub.id AS sub_order_id
                FROM zonda_market.checkout_sub_order sub
                JOIN zonda_market.seller_profile profile ON profile.id = sub.seller_id
                JOIN zonda_market.user_account account ON account.id = profile.account_id
                WHERE sub.order_id = :order AND NOT account.deleted
                """ + filter).param("order", event.orderId());
        if (event.subOrderId() != null) query.param("sub", event.subOrderId());
        return query.query(NotificationStore::recipient).list();
    }

    public UUID disputeId(UUID subOrder) {
        return sql.sql("SELECT id FROM zonda_market.dispute WHERE sub_order_id = :sub")
                .param("sub", subOrder).query(UUID.class).single();
    }

    public void enqueue(Event event, Recipient recipient, Type type, UUID resource, String href, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.purchase_notification
                    (id, event_id, recipient_id, type, resource_id, target_path, created_at, mail_recipient, mail_next_attempt_at)
                VALUES (:id, :event, :recipient, :type, :resource, :href, :created, :email, :next)
                ON CONFLICT (event_id, recipient_id) DO NOTHING
                """).param("id", UUID.randomUUID()).param("event", event.id()).param("recipient", recipient.id())
                .param("type", type.name()).param("resource", resource).param("href", href).param("created", at(event.createdAt()))
                .param("email", recipient.verifiedEmail()).param("next", recipient.verifiedEmail() == null ? null : at(now)).update();
    }

    public void projected(UUID event, Instant now) {
        sql.sql("""
                UPDATE zonda_market.checkout_outbox SET notification_pending = FALSE, delivered_at = COALESCE(delivered_at, :now)
                WHERE id = :event
                """).param("event", event).param("now", at(now)).update();
    }

    public List<NotificationResponse> page(UUID recipient, int page, int size) {
        return sql.sql("""
                SELECT id, type, resource_id, target_path, created_at, read_at
                FROM zonda_market.purchase_notification WHERE recipient_id = :recipient
                ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit
                """).param("recipient", recipient).param("offset", (long) page * size).param("limit", size + 1)
                .query(NotificationStore::notification).list();
    }

    public long unreadCount(UUID recipient) {
        return sql.sql("""
                SELECT count(*) FROM zonda_market.purchase_notification WHERE recipient_id = :recipient AND read_at IS NULL
                """).param("recipient", recipient).query(Long.class).single();
    }

    public Optional<NotificationResponse> markRead(UUID recipient, UUID id, Instant now) {
        return sql.sql("""
                UPDATE zonda_market.purchase_notification SET read_at = COALESCE(read_at, :now)
                WHERE id = :id AND recipient_id = :recipient
                RETURNING id, type, resource_id, target_path, created_at, read_at
                """).param("recipient", recipient).param("id", id).param("now", at(now))
                .query(NotificationStore::notification).optional();
    }

    public Optional<Delivery> claimMail(Instant now, Instant until) {
        UUID lease = UUID.randomUUID();
        return sql.sql("""
                WITH next AS (
                    SELECT notification.id FROM zonda_market.purchase_notification notification
                    JOIN zonda_market.user_account account ON account.id = notification.recipient_id
                    WHERE notification.mail_sent_at IS NULL AND notification.mail_next_attempt_at <= :now
                        AND (notification.mail_lease_until IS NULL OR notification.mail_lease_until <= :now)
                        AND NOT account.deleted AND account.email_verified AND account.email = notification.mail_recipient
                    ORDER BY notification.mail_next_attempt_at, notification.id LIMIT 1 FOR UPDATE OF notification SKIP LOCKED
                )
                UPDATE zonda_market.purchase_notification notification
                SET mail_lease_id = :lease, mail_lease_until = :until, mail_attempts = notification.mail_attempts + 1
                FROM next WHERE notification.id = next.id
                RETURNING notification.id, notification.type, notification.mail_recipient,
                    notification.target_path, notification.mail_attempts
                """).param("now", at(now)).param("until", at(until)).param("lease", lease)
                .query((row, i) -> new Delivery(row.getObject("id", UUID.class), lease, Type.valueOf(row.getString("type")),
                        row.getString("mail_recipient"), row.getString("target_path"), row.getInt("mail_attempts"))).optional();
    }

    public void sent(Delivery delivery, Instant now, String providerId) {
        sql.sql("""
                UPDATE zonda_market.purchase_notification
                SET mail_sent_at = :now, mail_provider_id = :provider, mail_lease_id = NULL, mail_lease_until = NULL
                WHERE id = :id AND mail_lease_id = :lease
                """).param("id", delivery.id()).param("lease", delivery.lease())
                .param("now", at(now)).param("provider", providerId).update();
    }

    public void retry(Delivery delivery, Instant next) {
        sql.sql("""
                UPDATE zonda_market.purchase_notification
                SET mail_next_attempt_at = :next, mail_lease_id = NULL, mail_lease_until = NULL
                WHERE id = :id AND mail_lease_id = :lease
                """).param("id", delivery.id()).param("lease", delivery.lease()).param("next", at(next)).update();
    }

    private static Recipient recipient(ResultSet row, int ignored) throws SQLException {
        return new Recipient(row.getObject("id", UUID.class), row.getString("verified_email"), row.getObject("sub_order_id", UUID.class));
    }

    private static NotificationResponse notification(ResultSet row, int ignored) throws SQLException {
        return new NotificationResponse(row.getObject("id", UUID.class), Type.valueOf(row.getString("type")),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getObject("read_at") != null,
                row.getObject("resource_id", UUID.class), row.getString("target_path"));
    }

    private static OffsetDateTime at(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
