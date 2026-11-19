package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.dto.StockDtos.Movement;
import com.zondamarket.catalog.dto.StockDtos.MovementPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockMovementStore {
    public record RecordedAdjustment(Movement movement, long expectedVersion) {}

    private final JdbcClient sql;

    StockMovementStore(JdbcClient sql) {
        this.sql = sql;
    }

    public Optional<RecordedAdjustment> findAdjustment(UUID skuId, String key) {
        return sql.sql("""
                select * from zonda_market.stock_movement where sku_id = :sku and idempotency_key = :key
                """).param("sku", skuId).param("key", key)
                .query((row, number) -> new RecordedAdjustment(read(row), row.getLong("expected_version"))).optional();
    }

    public void insert(Movement movement, String key, long expectedVersion) {
        sql.sql("""
                insert into zonda_market.stock_movement
                    (id, sku_id, actor_id, trace_id, idempotency_key, delta, reason, before_stock, after_stock,
                     expected_version, resulting_version, created_at)
                values (:id, :sku, :actor, :trace, :key, :delta, :reason, :before, :after, :expected, :version, :created)
                """).param("id", movement.id()).param("sku", movement.skuId()).param("actor", movement.actorId())
                .param("trace", movement.traceId()).param("key", key).param("delta", movement.delta())
                .param("reason", movement.reason()).param("before", movement.beforeStock()).param("after", movement.afterStock())
                .param("expected", expectedVersion).param("version", movement.version())
                .param("created", movement.createdAt().atOffset(ZoneOffset.UTC)).update();
    }

    public MovementPage page(UUID skuId, int page, int size) {
        long total = sql.sql("select count(*) from zonda_market.stock_movement where sku_id = :sku")
                .param("sku", skuId).query(Long.class).single();
        var items = sql.sql("""
                select * from zonda_market.stock_movement
                where sku_id = :sku order by resulting_version desc limit :size offset :offset
                """).param("sku", skuId).param("size", size).param("offset", (long) page * size)
                .query((row, number) -> read(row)).list();
        return new MovementPage(items, page, size, total, total == 0 ? 0 : (total - 1) / size + 1);
    }

    private static Movement read(ResultSet row) throws SQLException {
        return new Movement(row.getObject("id", UUID.class), row.getObject("sku_id", UUID.class),
                row.getInt("delta"), row.getString("reason"), row.getInt("before_stock"), row.getInt("after_stock"),
                row.getObject("actor_id", UUID.class), row.getObject("trace_id", UUID.class),
                row.getLong("resulting_version"), row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
