package com.zondamarket.checkout.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SellerMetricsStore {
    private static final String ACTIVITY = """
            WITH activity AS (
                SELECT entry.sub_order_id, entry.recorded_at AS event_at,
                       entry.merchandise, entry.shipping, entry.commission, 0.00 AS refunds, 0.00 AS reversed
                FROM zonda_market.checkout_sub_order_settlement entry
                JOIN zonda_market.checkout_sub_order sub ON sub.id = entry.sub_order_id
                WHERE sub.seller_id = :seller AND entry.recorded_at >= :start AND entry.recorded_at < :end
                UNION ALL
                SELECT refund.sub_order_id, refund.confirmed_at,
                       0.00, 0.00, 0.00, refund.merchandise_refunded + refund.shipping_refunded, refund.commission_reversed
                FROM zonda_market.checkout_sub_order_refund refund
                JOIN zonda_market.checkout_sub_order sub ON sub.id = refund.sub_order_id
                WHERE sub.seller_id = :seller AND refund.confirmed_at >= :start AND refund.confirmed_at < :end
            )
            """;
    private static final String SUMS = """
            sum(merchandise) AS merchandise, sum(shipping) AS shipping, sum(commission) AS commission,
            sum(refunds) AS refunds, sum(reversed) AS reversed
            """;

    private final JdbcClient sql;

    SellerMetricsStore(JdbcClient sql) {
        this.sql = sql;
    }

    public record Amounts(BigDecimal merchandise, BigDecimal shipping, BigDecimal commission,
                          BigDecimal refunds, BigDecimal reversed) {
        private static final BigDecimal ZERO = new BigDecimal("0.00");
        public static final Amounts EMPTY = new Amounts(ZERO, ZERO, ZERO, ZERO, ZERO);

        public BigDecimal gross() { return merchandise.add(shipping); }

        public BigDecimal net() { return gross().subtract(commission).subtract(refunds).add(reversed); }

        public Amounts plus(Amounts other) {
            return new Amounts(merchandise.add(other.merchandise), shipping.add(other.shipping),
                    commission.add(other.commission), refunds.add(other.refunds), reversed.add(other.reversed));
        }
    }

    public record Entry(UUID subOrderId, String status, Instant recordedAt, Instant lastActivityAt, Amounts amounts) {}

    public Map<LocalDate, Amounts> daily(UUID seller, Instant start, Instant end, String zone) {
        return period(ACTIVITY + "SELECT (event_at AT TIME ZONE :zone)::date AS day, " + SUMS
                + "FROM activity GROUP BY day", seller, start, end).param("zone", zone)
                .query((row, i) -> Map.entry(row.getObject("day", LocalDate.class), amounts(row)))
                .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<String, Long> states(UUID seller, Instant start, Instant end) {
        return period("""
                SELECT sub.status, count(*) AS quantity
                FROM zonda_market.checkout_sub_order_settlement entry
                JOIN zonda_market.checkout_sub_order sub ON sub.id = entry.sub_order_id
                WHERE sub.seller_id = :seller AND entry.recorded_at >= :start AND entry.recorded_at < :end
                GROUP BY sub.status
                """, seller, start, end).query((row, i) -> Map.entry(row.getString("status"), row.getLong("quantity")))
                .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<Entry> settlements(UUID seller, Instant start, Instant end, int page, int size) {
        return period(ACTIVITY + """
                SELECT totals.*, sub.status, entry.recorded_at
                FROM (
                    SELECT sub_order_id, max(event_at) AS last_activity_at,
                """ + SUMS + """
                    FROM activity GROUP BY sub_order_id
                ) totals
                JOIN zonda_market.checkout_sub_order sub ON sub.id = totals.sub_order_id
                JOIN zonda_market.checkout_sub_order_settlement entry ON entry.sub_order_id = totals.sub_order_id
                ORDER BY totals.last_activity_at DESC, totals.sub_order_id DESC OFFSET :offset LIMIT :limit
                """, seller, start, end).param("offset", (long) page * size).param("limit", size + 1)
                .query((row, i) -> new Entry(row.getObject("sub_order_id", UUID.class), row.getString("status"),
                        row.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        row.getObject("last_activity_at", OffsetDateTime.class).toInstant(), amounts(row))).list();
    }

    private JdbcClient.StatementSpec period(String query, UUID seller, Instant start, Instant end) {
        return sql.sql(query).param("seller", seller).param("start", start.atOffset(ZoneOffset.UTC))
                .param("end", end.atOffset(ZoneOffset.UTC));
    }

    private static Amounts amounts(ResultSet row) throws SQLException {
        return new Amounts(row.getBigDecimal("merchandise"), row.getBigDecimal("shipping"),
                row.getBigDecimal("commission"), row.getBigDecimal("refunds"), row.getBigDecimal("reversed"));
    }
}
