package com.zondamarket.checkout.repository;

import com.zondamarket.checkout.dto.DisputeDtos.*;
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
public class DisputeStore {
    static final String ACTIVE_STATUSES = "('ABIERTA', 'EN_MEDIACION', 'REEMBOLSO_PENDIENTE')";
    private static final String CASES = """
            SELECT dispute.*, sub.order_id, purchase.buyer_id, seller.account_id AS seller_account_id
            FROM zonda_market.dispute dispute
            JOIN zonda_market.checkout_sub_order sub ON sub.id = dispute.sub_order_id
            JOIN zonda_market.checkout_order purchase ON purchase.id = sub.order_id
            JOIN zonda_market.seller_profile seller ON seller.id = sub.seller_id
            """;
    private static final String MESSAGE_COLUMNS = "id, author_role, body, created_at";

    private final JdbcClient sql;

    DisputeStore(JdbcClient sql) {
        this.sql = sql;
    }

    public record Case(Summary summary, UUID orderId, String description, Participants participants) {}

    public Optional<Case> forSubOrder(UUID subOrder) {
        return sql.sql(CASES + " WHERE sub_order_id = :sub").param("sub", subOrder)
                .query(DisputeStore::readCase).optional();
    }

    public Optional<Case> visible(UUID id, UUID actor, boolean admin, boolean lock) {
        return sql.sql(CASES + """
                WHERE dispute.id = :id AND (:admin OR purchase.buyer_id = :actor OR seller.account_id = :actor)
                """ + (lock ? " FOR UPDATE OF dispute" : "")).param("id", id).param("actor", actor)
                .param("admin", admin).query(DisputeStore::readCase).optional();
    }

    public boolean active(UUID subOrder) {
        return sql.sql("""
                SELECT EXISTS (SELECT FROM zonda_market.dispute WHERE sub_order_id = :sub AND status IN
                """ + ACTIVE_STATUSES + ")").param("sub", subOrder).query(Boolean.class).single();
    }

    public void open(UUID id, UUID subOrder, String reason, String description, Instant now) {
        sql.sql("""
                INSERT INTO zonda_market.dispute (id, sub_order_id, status, reason, description, opened_at)
                VALUES (:id, :sub, 'ABIERTA', :reason, :description, :now)
                """).param("id", id).param("sub", subOrder).param("reason", reason)
                .param("description", description).param("now", at(now)).update();
    }

    public void assign(UUID id, UUID admin, Instant now) {
        sql.sql("""
                UPDATE zonda_market.dispute SET status = 'EN_MEDIACION', assigned_by = :admin, assigned_at = :now
                WHERE id = :id
                """).param("id", id).param("admin", admin).param("now", at(now)).update();
    }

    public List<Summary> queue(Status status, Instant since, int page, int size) {
        var query = sql.sql("""
                SELECT id, sub_order_id, status, reason, opened_at, assigned_at FROM zonda_market.dispute
                WHERE TRUE
                """ + (since == null ? "" : " AND opened_at >= :since") + (status == null ? "" : " AND status = :status") + """
                 ORDER BY opened_at, id OFFSET :offset LIMIT :limit
                """).param("offset", (long) page * size).param("limit", size + 1);
        if (since != null) query.param("since", at(since));
        if (status != null) query.param("status", status.name());
        return query.query(DisputeStore::summary).list();
    }

    public List<Message> messages(UUID dispute, int page, int size) {
        return sql.sql("SELECT " + MESSAGE_COLUMNS + """
                 FROM zonda_market.dispute_message WHERE dispute_id = :id
                ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit
                """).param("id", dispute).param("offset", (long) page * size).param("limit", size + 1)
                .query(DisputeStore::message).list();
    }

    public Optional<Message> replay(UUID dispute, UUID author, UUID key) {
        return sql.sql("SELECT " + MESSAGE_COLUMNS + """
                 FROM zonda_market.dispute_message WHERE dispute_id = :id AND author_id = :author AND request_key = :key
                """).param("id", dispute).param("author", author).param("key", key)
                .query(DisputeStore::message).optional();
    }

    public Message message(UUID dispute, UUID author, ParticipantRole role, String body, UUID key, Instant now) {
        return sql.sql("""
                INSERT INTO zonda_market.dispute_message (id, dispute_id, author_id, author_role, body, request_key, created_at)
                VALUES (:id, :dispute, :author, :role, :body, :key, :now)
                RETURNING
                """ + MESSAGE_COLUMNS).param("id", UUID.randomUUID()).param("dispute", dispute)
                .param("author", author).param("role", role.name()).param("body", body)
                .param("key", key).param("now", at(now)).query(DisputeStore::message).single();
    }

    private static Case readCase(ResultSet row, int index) throws SQLException {
        return new Case(summary(row, index), row.getObject("order_id", UUID.class), row.getString("description"),
                new Participants(row.getObject("buyer_id", UUID.class), row.getObject("seller_account_id", UUID.class),
                    row.getObject("assigned_by", UUID.class)));
    }

    private static Summary summary(ResultSet row, int ignored) throws SQLException {
        return new Summary(row.getObject("id", UUID.class), row.getObject("sub_order_id", UUID.class),
                Status.valueOf(row.getString("status")), row.getString("reason"), instant(row, "opened_at"),
                instant(row, "assigned_at"));
    }

    private static Message message(ResultSet row, int ignored) throws SQLException {
        return new Message(row.getObject("id", UUID.class), ParticipantRole.valueOf(row.getString("author_role")),
                row.getString("body"), instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var time = row.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    private static OffsetDateTime at(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
