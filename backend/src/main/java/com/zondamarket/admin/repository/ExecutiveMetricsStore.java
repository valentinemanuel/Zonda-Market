package com.zondamarket.admin.repository;

import com.zondamarket.admin.dto.ExecutiveMetricsResponse.Category;
import com.zondamarket.admin.dto.ExecutiveMetricsResponse.Seller;
import com.zondamarket.shared.time.ReportingPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExecutiveMetricsStore {
    // Settlements and confirmed post-sale adjustments share the seller ledger used by E5-04.
    private static final String ACTIVITY = """
            WITH activity AS (
                SELECT sub_order_id, recorded_at AS event_at, merchandise + shipping AS gmv,
                       0.00 AS refunds, commission
                FROM zonda_market.checkout_sub_order_settlement
                WHERE recorded_at >= :start AND recorded_at < :end
                UNION ALL
                SELECT sub_order_id, confirmed_at, 0.00, merchandise_refunded + shipping_refunded,
                       -commission_reversed
                FROM zonda_market.checkout_sub_order_refund
                WHERE confirmed_at >= :start AND confirmed_at < :end
            )
            """;

    public record Totals(BigDecimal gmv, BigDecimal refunds, BigDecimal commission, long paidOrders, long disputes) {
        private static final BigDecimal ZERO = new BigDecimal("0.00");
        public static final Totals EMPTY = new Totals(ZERO, ZERO, ZERO, 0, 0);

        public Totals plus(Totals other) {
            return new Totals(gmv.add(other.gmv), refunds.add(other.refunds), commission.add(other.commission),
                    paidOrders + other.paidOrders, disputes + other.disputes);
        }
    }

    private final JdbcClient sql;

    ExecutiveMetricsStore(JdbcClient sql) {
        this.sql = sql;
    }

    public Map<LocalDate, Totals> daily(ReportingPeriod range) {
        return period(ACTIVITY + """
                , events AS (
                    SELECT event_at, gmv, refunds, commission, 0 AS paid, 0 AS disputes FROM activity
                    UNION ALL
                    SELECT created_at, 0.00, 0.00, 0.00, 1, 0
                    FROM zonda_market.checkout_payment_outcome
                    WHERE result = 'APPROVED' AND created_at >= :start AND created_at < :end
                    UNION ALL
                    SELECT opened_at, 0.00, 0.00, 0.00, 0, 1
                    FROM zonda_market.dispute WHERE opened_at >= :start AND opened_at < :end
                )
                SELECT (event_at AT TIME ZONE :zone)::date AS day, sum(gmv) AS gmv, sum(refunds) AS refunds,
                       sum(commission) AS commission, sum(paid) AS paid, sum(disputes) AS disputes
                FROM events GROUP BY day
                """, range).param("zone", ReportingPeriod.ZONE.getId())
                .query((row, i) -> Map.entry(row.getObject("day", LocalDate.class),
                        new Totals(row.getBigDecimal("gmv"), row.getBigDecimal("refunds"),
                                row.getBigDecimal("commission"), row.getLong("paid"), row.getLong("disputes"))))
                .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public long openCases() {
        return sql.sql("""
                SELECT count(*) FROM zonda_market.dispute
                WHERE status IN ('ABIERTA', 'EN_MEDIACION', 'REEMBOLSO_PENDIENTE')
                """).query(Long.class).single();
    }

    public List<Seller> sellers(ReportingPeriod range, int limit) {
        return period(ACTIVITY + """
                SELECT seller.id, seller.display_name, sum(activity.gmv) AS gmv,
                       sum(activity.refunds) AS refunds, sum(activity.commission) AS commission
                FROM activity
                JOIN zonda_market.checkout_sub_order sub ON sub.id = activity.sub_order_id
                JOIN zonda_market.seller_profile seller ON seller.id = sub.seller_id
                GROUP BY seller.id ORDER BY gmv DESC, refunds DESC, seller.id LIMIT :limit
                """, range).param("limit", limit)
                .query((row, i) -> new Seller(row.getObject("id", UUID.class), row.getString("display_name"),
                        row.getBigDecimal("gmv").toPlainString(), row.getBigDecimal("refunds").toPlainString(),
                        row.getBigDecimal("commission").toPlainString())).list();
    }

    public List<Category> categories(ReportingPeriod range, int limit) {
        return period("""
                SELECT category.id, category.name, sum(line.unit_price * line.quantity) AS merchandise,
                       sum(line.commission) AS commission
                FROM zonda_market.checkout_sub_order_settlement entry
                JOIN zonda_market.checkout_order_line line ON line.sub_order_id = entry.sub_order_id
                JOIN zonda_market.category category ON category.id = line.category_id
                WHERE entry.recorded_at >= :start AND entry.recorded_at < :end
                GROUP BY category.id ORDER BY merchandise DESC, category.id LIMIT :limit
                """, range).param("limit", limit)
                .query((row, i) -> new Category(row.getObject("id", UUID.class), row.getString("name"),
                        row.getBigDecimal("merchandise").toPlainString(),
                        row.getBigDecimal("commission").toPlainString())).list();
    }

    private JdbcClient.StatementSpec period(String query, ReportingPeriod range) {
        return sql.sql(query).param("start", range.start().atOffset(ZoneOffset.UTC))
                .param("end", range.end().atOffset(ZoneOffset.UTC));
    }
}
