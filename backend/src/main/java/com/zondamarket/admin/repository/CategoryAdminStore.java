package com.zondamarket.admin.repository;

import com.zondamarket.admin.dto.CategoryAdminDtos.CategoryRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CategoryAdminStore {
    private static final TypeReference<Map<String, List<String>>> SCHEMA = new TypeReference<>() {};
    private static final String ROWS = """
            SELECT c.*, EXISTS (SELECT FROM zonda_market.category child
                WHERE child.parent_id = c.id AND NOT child.deleted) AS has_children,
                (SELECT count(*) FROM zonda_market.listing l
                 WHERE l.category_id = c.id AND NOT l.deleted AND l.status = 'ACTIVA') AS active_listings
            FROM zonda_market.category c
            """;

    private final JdbcClient sql;
    private final ObjectMapper json;

    CategoryAdminStore(JdbcClient sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    public List<CategoryRow> all() {
        return rows(sql.sql(ROWS + " ORDER BY c.name, c.id")).list();
    }

    public CategoryRow find(UUID id) {
        return findOptional(id).orElseThrow();
    }

    public Optional<CategoryRow> findOptional(UUID id) {
        return rows(sql.sql(ROWS + " WHERE c.id = :id").param("id", id)).optional();
    }

    private JdbcClient.MappedQuerySpec<CategoryRow> rows(JdbcClient.StatementSpec query) {
        return query.query((rs, row) -> new CategoryRow(
                rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("name"), rs.getString("slug"), rs.getInt("commission_bps"),
                json.readValue(rs.getString("attribute_schema"), SCHEMA), rs.getLong("version"),
                rs.getBoolean("deleted"), rs.getLong("active_listings"), rs.getBoolean("has_children")));
    }

    public boolean slugInUse(String slug, UUID id) {
        return sql.sql("SELECT EXISTS (SELECT FROM zonda_market.category WHERE slug = :slug AND id <> :id)")
                .param("slug", slug).param("id", id).query(Boolean.class).single();
    }

    public boolean hasIncompatibleSkus(UUID id, Map<String, Object> schema) {
        return sql.sql("""
                WITH proposed AS (SELECT CAST(:schema AS jsonb) AS attributes)
                SELECT EXISTS (
                    SELECT FROM zonda_market.listing l
                    JOIN zonda_market.listing_sku sku ON sku.listing_id = l.id CROSS JOIN proposed
                    WHERE l.category_id = :id AND NOT l.deleted AND NOT sku.deleted
                      AND (EXISTS (SELECT FROM jsonb_each(proposed.attributes) field
                                   WHERE (field.value @> jsonb_build_array(sku.attributes -> field.key)) IS NOT TRUE)
                           OR EXISTS (SELECT FROM jsonb_object_keys(sku.attributes) AS key(name)
                                      WHERE NOT (jsonb_exists(proposed.attributes, key.name))))
                )
                """).param("id", id).param("schema", json.writeValueAsString(schema)).query(Boolean.class).single();
    }

    public void save(UUID id, UUID parent, String name, String slug, int bps, Map<String, Object> schema) {
        sql.sql("""
                INSERT INTO zonda_market.category
                    (id, parent_id, name, slug, commission_bps, attribute_schema, created_at, updated_at)
                VALUES (:id, :parent, :name, :slug, :bps, CAST(:schema AS jsonb), statement_timestamp(), statement_timestamp())
                ON CONFLICT (id) DO UPDATE SET parent_id = EXCLUDED.parent_id, name = EXCLUDED.name,
                    slug = EXCLUDED.slug, commission_bps = EXCLUDED.commission_bps,
                    attribute_schema = EXCLUDED.attribute_schema, version = category.version + 1,
                    updated_at = EXCLUDED.updated_at
                """).param("id", id).param("parent", parent).param("name", name).param("slug", slug)
                .param("bps", bps).param("schema", json.writeValueAsString(schema)).update();
    }

    public void archive(UUID id) {
        sql.sql("""
                UPDATE zonda_market.category SET deleted = TRUE, version = version + 1, updated_at = statement_timestamp()
                WHERE id = :id
                """).param("id", id).update();
    }

    public void closeCommission(UUID id) {
        sql.sql("""
                UPDATE zonda_market.category_commission_period SET valid_until =
                    greatest(valid_from, (SELECT updated_at FROM zonda_market.category WHERE id = :id))
                WHERE category_id = :id AND valid_until IS NULL
                """).param("id", id).update();
    }

    public void recordCommission(UUID id, UUID actor, UUID trace) {
        sql.sql("""
                INSERT INTO zonda_market.category_commission_period
                    (category_id, category_version, commission_bps, valid_from, actor_id, trace_id)
                SELECT c.id, c.version, c.commission_bps, greatest(c.updated_at, previous.valid_until), :actor, :trace
                FROM zonda_market.category c
                LEFT JOIN LATERAL (
                    SELECT valid_until FROM zonda_market.category_commission_period
                    WHERE category_id = c.id ORDER BY category_version DESC LIMIT 1
                ) previous ON TRUE
                WHERE c.id = :id
                """).param("id", id).param("actor", actor).param("trace", trace).update();
    }
}
