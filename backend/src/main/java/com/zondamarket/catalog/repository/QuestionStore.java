package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.dto.QuestionDtos.PendingQuestion;
import com.zondamarket.catalog.dto.QuestionDtos.Question;
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
public class QuestionStore {
    private final JdbcClient sql;

    QuestionStore(JdbcClient sql) {
        this.sql = sql;
    }

    public boolean visible(UUID listing) {
        return sql.sql("SELECT EXISTS (SELECT 1 FROM zonda_market.public_listing WHERE id = :id)")
                .param("id", listing).query(Boolean.class).single();
    }

    // The same listing row is locked by publish, archive and seller editing commands.
    public boolean lockActiveListing(UUID listing) {
        return sql.sql("""
                SELECT id FROM zonda_market.listing
                WHERE id = :id AND status = 'ACTIVA' AND NOT deleted FOR UPDATE
                """).param("id", listing).query(UUID.class).optional().isPresent();
    }

    public List<Question> list(UUID listing, long offset, int limit) {
        return sql.sql("""
                SELECT id, question_text, created_at, answer_text, answered_at
                FROM zonda_market.listing_question WHERE listing_id = :listing AND NOT deleted
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """).param("listing", listing).param("offset", offset).param("limit", limit)
                .query(QuestionStore::readQuestion).list();
    }

    public Optional<UUID> ownedListing(UUID actor, UUID question) {
        return sql.sql("""
                SELECT l.id FROM zonda_market.listing_question q
                JOIN zonda_market.public_listing l ON l.id = q.listing_id
                WHERE q.id = :question AND NOT q.deleted AND l.seller_account_id = :actor
                """).param("actor", actor).param("question", question).query(UUID.class).optional();
    }

    public Optional<Question> find(UUID id) {
        return sql.sql("""
                SELECT id, question_text, created_at, answer_text, answered_at
                FROM zonda_market.listing_question WHERE id = :id AND NOT deleted
                """).param("id", id).query(QuestionStore::readQuestion).optional();
    }

    public List<PendingQuestion> pending(UUID actor, long offset, int limit) {
        return sql.sql("""
                SELECT q.id, q.question_text, q.created_at, l.slug, l.title
                FROM zonda_market.listing_question q JOIN zonda_market.public_listing l ON l.id = q.listing_id
                WHERE l.seller_account_id = :actor AND NOT q.deleted AND q.answered_at IS NULL
                ORDER BY q.created_at, q.id LIMIT :limit OFFSET :offset
                """).param("actor", actor).param("limit", limit).param("offset", offset)
                .query((rs, row) -> new PendingQuestion(rs.getObject("id", UUID.class), rs.getString("question_text"),
                        instant(rs, "created_at"), rs.getString("slug"), rs.getString("title"))).list();
    }

    public Question add(UUID listing, UUID actor, String text, Instant now, UUID trace) {
        return sql.sql("""
                INSERT INTO zonda_market.listing_question
                    (id, listing_id, author_id, question_text, created_at, updated_at, question_trace_id)
                VALUES (:id, :listing, :actor, :text, :now, :now, :trace)
                RETURNING id, question_text, created_at, answer_text, answered_at
                """).param("id", UUID.randomUUID()).param("listing", listing).param("actor", actor)
                .param("text", text).param("now", utc(now)).param("trace", trace)
                .query(QuestionStore::readQuestion).single();
    }

    public Question answer(UUID id, UUID actor, String text, Instant now, UUID trace) {
        return sql.sql("""
                UPDATE zonda_market.listing_question
                SET answer_text = :text, answered_at = :now, updated_at = :now, answered_by = :actor, answer_trace_id = :trace
                WHERE id = :id
                RETURNING id, question_text, created_at, answer_text, answered_at
                """).param("id", id).param("actor", actor).param("text", text).param("now", utc(now)).param("trace", trace)
                .query(QuestionStore::readQuestion).single();
    }

    public boolean consumeBudget(UUID actor, String action, Instant window, int maximum) {
        return sql.sql("""
                INSERT INTO zonda_market.listing_question_rate AS budget (account_id, action, window_start, used)
                VALUES (:actor, :action, :window, 1)
                ON CONFLICT (account_id, action) DO UPDATE SET
                    used = CASE WHEN budget.window_start < EXCLUDED.window_start THEN 1 ELSE budget.used + 1 END,
                    window_start = GREATEST(budget.window_start, EXCLUDED.window_start)
                WHERE budget.window_start < EXCLUDED.window_start OR budget.used < :maximum
                """).param("actor", actor).param("action", action).param("window", utc(window))
                .param("maximum", maximum).update() == 1;
    }

    private static Question readQuestion(ResultSet rs, int row) throws SQLException {
        return new Question(rs.getObject("id", UUID.class), rs.getString("question_text"), instant(rs, "created_at"),
                rs.getString("answer_text"), instant(rs, "answered_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
