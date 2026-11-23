package com.zondamarket.admin.repository;

import com.zondamarket.admin.dto.ModerationDtos.AccountRow;
import com.zondamarket.admin.dto.ModerationDtos.Audit;
import com.zondamarket.admin.dto.ModerationDtos.ListingRow;
import com.zondamarket.admin.dto.ModerationDtos.OrderCase;
import com.zondamarket.admin.dto.ModerationDtos.State;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ModerationStore {
    public enum Target {
        LISTING("listing_id"), ACCOUNT("account_id");

        private final String auditColumn;

        Target(String auditColumn) {
            this.auditColumn = auditColumn;
        }
    }

    public record ListingState(boolean hidden, long version) {}
    public record AccountState(boolean suspended, String role, long version) {}

    private final JdbcClient sql;

    ModerationStore(JdbcClient sql) {
        this.sql = sql;
    }

    public List<ListingRow> listings(String search, String state, long offset, int limit) {
        return sql.sql("""
                SELECT l.id, l.title, l.slug, account.email AS seller_email, l.status, l.version,
                       l.moderation_hidden AS hidden, visible.id IS NOT NULL AS visible,
                       account.suspended AS seller_suspended
                FROM zonda_market.listing l
                JOIN zonda_market.seller_profile seller ON seller.id = l.seller_id
                JOIN zonda_market.user_account account ON account.id = seller.account_id
                LEFT JOIN zonda_market.public_listing visible ON visible.id = l.id
                WHERE NOT l.deleted
                  AND (:q = '' OR strpos(lower(l.title), lower(:q)) > 0 OR strpos(lower(account.email), lower(:q)) > 0)
                  AND (:state = '' OR (:state = 'HIDDEN' AND l.moderation_hidden)
                       OR (:state = 'VISIBLE' AND visible.id IS NOT NULL))
                ORDER BY l.created_at DESC, l.id LIMIT :limit OFFSET :offset
                """).param("q", search).param("state", state).param("offset", offset).param("limit", limit)
                .query(ListingRow.class).list();
    }

    public List<AccountRow> accounts(String search, String state, long offset, int limit) {
        return sql.sql("""
                SELECT id, email, display_name, role, suspended, version
                FROM zonda_market.user_account
                WHERE NOT deleted
                  AND (:q = '' OR strpos(lower(email), lower(:q)) > 0 OR strpos(lower(display_name), lower(:q)) > 0)
                  AND (:state = '' OR suspended = (:state = 'SUSPENDED'))
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """).param("q", search).param("state", state).param("offset", offset).param("limit", limit)
                .query(AccountRow.class).list();
    }

    public Optional<ListingState> lockListing(UUID id) {
        return sql.sql("""
                SELECT moderation_hidden AS hidden, version FROM zonda_market.listing
                WHERE id = :id AND NOT deleted FOR UPDATE
                """).param("id", id).query(ListingState.class).optional();
    }

    public Optional<AccountState> lockAccount(UUID id) {
        return sql.sql("""
                SELECT suspended, role, version FROM zonda_market.user_account
                WHERE id = :id AND NOT deleted FOR UPDATE
                """).param("id", id).query(AccountState.class).optional();
    }

    public void setListingHidden(UUID id, boolean hidden, OffsetDateTime now) {
        sql.sql("""
                UPDATE zonda_market.listing SET moderation_hidden = :hidden, version = version + 1, updated_at = :now
                WHERE id = :id
                """).param("id", id).param("hidden", hidden).param("now", now).update();
    }

    public void setAccountSuspended(UUID id, boolean suspended, OffsetDateTime now) {
        sql.sql("""
                UPDATE zonda_market.user_account SET suspended = :suspended, version = version + 1, updated_at = :now
                WHERE id = :id
                """).param("id", id).param("suspended", suspended).param("now", now).update();
    }

    public Audit record(Target target, UUID id, UUID actor, String actorEmail, String action, String reason,
                        State before, State after, long version, UUID trace, OffsetDateTime now) {
        sql.sql("""
                INSERT INTO zonda_market.moderation_audit
                    (id, %s, actor_id, action, reason, before_state, after_state, target_version, trace_id, created_at)
                VALUES (:event, :target, :actor, :action, :reason, :before, :after, :version, :trace, :now)
                """.formatted(target.auditColumn)).param("event", UUID.randomUUID()).param("target", id)
                .param("actor", actor).param("action", action).param("reason", reason)
                .param("before", before.name()).param("after", after.name()).param("version", version)
                .param("trace", trace).param("now", now).update();
        return new Audit(actor, actorEmail, action, reason, before.name(), after.name(), version, trace, now);
    }

    public List<Audit> history(Target target, UUID id, long offset, int limit) {
        return sql.sql("""
                SELECT a.actor_id, actor.email AS actor_email, a.action, a.reason, a.before_state, a.after_state,
                       a.target_version, a.trace_id, a.created_at
                FROM zonda_market.moderation_audit a
                JOIN zonda_market.user_account actor ON actor.id = a.actor_id
                WHERE a.%s = :id ORDER BY a.target_version DESC LIMIT :limit OFFSET :offset
                """.formatted(target.auditColumn)).param("id", id).param("offset", offset).param("limit", limit)
                .query(Audit.class).list();
    }

    public List<OrderCase> cases(Target target, UUID id, long offset, int limit) {
        String match = switch (target) {
            case ACCOUNT -> "(purchase.buyer_id = :id OR seller.account_id = :id)";
            case LISTING -> """
                    EXISTS (SELECT 1 FROM zonda_market.checkout_order_line line
                        JOIN zonda_market.listing_sku sku ON sku.id = line.sku_id
                        WHERE line.sub_order_id = sub.id AND sku.listing_id = :id)
                    """;
        };
        return sql.sql("""
                SELECT purchase.id AS order_id, sub.id AS sub_order_id, purchase.status AS order_status,
                       sub.status AS sub_order_status, sub.seller_name, sub.created_at,
                       (buyer.suspended OR seller_account.suspended OR EXISTS (
                           SELECT 1 FROM zonda_market.checkout_order_line line
                           JOIN zonda_market.listing_sku sku ON sku.id = line.sku_id
                           JOIN zonda_market.listing listing ON listing.id = sku.listing_id
                           WHERE line.sub_order_id = sub.id AND listing.moderation_hidden
                       )) AS requires_follow_up
                FROM zonda_market.checkout_sub_order sub
                JOIN zonda_market.checkout_order purchase ON purchase.id = sub.order_id
                JOIN zonda_market.seller_profile seller ON seller.id = sub.seller_id
                JOIN zonda_market.user_account buyer ON buyer.id = purchase.buyer_id
                JOIN zonda_market.user_account seller_account ON seller_account.id = seller.account_id
                WHERE (%s) AND (purchase.status IN ('RESERVADA', 'PAGO_PENDIENTE')
                    OR (purchase.status IN ('PAGADA', 'REEMBOLSO_PARCIAL') AND sub.status IN ('PREPARANDO', 'DESPACHADA')))
                ORDER BY sub.created_at DESC, sub.id LIMIT :limit OFFSET :offset
                """.formatted(match)).param("id", id).param("offset", offset).param("limit", limit)
                .query(OrderCase.class).list();
    }
}
