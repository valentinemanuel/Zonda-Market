package com.zondamarket.review.repository;

import com.zondamarket.review.dto.ReviewDtos.ReviewResponse;
import com.zondamarket.review.dto.ReviewDtos.SellerReputationResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewStore {
    private static final String VISIBLE_REVIEWS = """
            FROM zonda_market.seller_review review
            JOIN zonda_market.checkout_sub_order sub ON sub.id = review.sub_order_id
            WHERE sub.seller_id = :seller AND NOT review.hidden
            """;
    private final JdbcClient sql;

    ReviewStore(JdbcClient sql) {
        this.sql = sql;
    }

    public Optional<ReviewResponse> insert(UUID subOrder, UUID author, int rating, String comment, Instant now, UUID trace) {
        return sql.sql("""
                INSERT INTO zonda_market.seller_review (id, sub_order_id, author_id, rating, comment, created_at, trace_id)
                VALUES (:id, :sub, :author, :rating, :comment, :now, :trace)
                ON CONFLICT (sub_order_id) DO NOTHING
                RETURNING id, rating, comment, created_at
                """).param("id", UUID.randomUUID()).param("sub", subOrder).param("author", author)
                .param("rating", rating).param("comment", comment).param("now", now.atOffset(ZoneOffset.UTC))
                .param("trace", trace).query(ReviewStore::review).optional();
    }

    public SellerReputationResponse reputation(UUID seller) {
        return sql.sql("SELECT round(avg(review.rating), 2) AS average, count(*) AS count " + VISIBLE_REVIEWS)
                .param("seller", seller)
                .query((row, i) -> new SellerReputationResponse(row.getBigDecimal("average"), row.getInt("count")))
                .single();
    }

    public List<ReviewResponse> list(UUID seller, int page, int size) {
        return sql.sql("SELECT review.id, review.rating, review.comment, review.created_at " + VISIBLE_REVIEWS + """
                ORDER BY review.created_at DESC, review.id DESC OFFSET :offset LIMIT :limit
                """).param("seller", seller).param("offset", (long) page * size).param("limit", size + 1)
                .query(ReviewStore::review).list();
    }

    private static ReviewResponse review(ResultSet row, int ignored) throws SQLException {
        var id = row.getObject("id", UUID.class);
        return new ReviewResponse(id, row.getInt("rating"), row.getString("comment"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                "Comprador " + id.toString().substring(0, 8).toUpperCase(Locale.ROOT));
    }
}
