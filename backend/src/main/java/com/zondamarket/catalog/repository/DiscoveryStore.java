package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.dto.DiscoveryDtos.AttributeFacet;
import com.zondamarket.catalog.dto.DiscoveryDtos.ConditionFilter;
import com.zondamarket.catalog.dto.DiscoveryDtos.ListingCardResponse;
import com.zondamarket.catalog.dto.DiscoveryDtos.ListingPage;
import com.zondamarket.catalog.dto.DiscoveryDtos.Search;
import com.zondamarket.catalog.dto.DiscoveryDtos.SellerFacet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DiscoveryStore {
    private static final String TEXT_QUERY = "plainto_tsquery('spanish', :term)";
    private final JdbcClient sql;

    DiscoveryStore(JdbcClient sql) {
        this.sql = sql;
    }

    public ListingPage search(Search search) {
        Map<String, Object> params = new LinkedHashMap<>();
        String categories = search.category() == null ? "" : """
                WITH RECURSIVE category_tree(id) AS (
                    SELECT id FROM zonda_market.category WHERE id = :category AND NOT deleted
                    UNION
                    SELECT c.id FROM zonda_market.category c JOIN category_tree t ON c.parent_id = t.id WHERE NOT c.deleted
                ),
                """;
        String matches = categories + (categories.isEmpty() ? "WITH " : "") + """
                matched AS (
                    SELECT l.id, l.seller_slug, l.seller_name, sku.id AS sku_id, sku.price,
                           sku.available_stock > 0 AS available, sku.attributes, product.condition
                    FROM zonda_market.public_listing l
                    JOIN zonda_market.listing details ON details.id = l.id
                    JOIN zonda_market.listing_sku sku ON sku.listing_id = l.id AND NOT sku.deleted
                    LEFT JOIN LATERAL (
                        SELECT value AS condition FROM (VALUES (COALESCE(
                            NULLIF(lower(btrim(details.specs ->> 'Condición')), ''),
                            NULLIF(lower(btrim(details.specs ->> 'condición')), ''),
                            substring(lower(btrim(l.title)) FROM ' · (nuevo|como nuevo|buen estado|segunda mano|usado)$')
                        ))) AS declared(value)
                        WHERE value IN ('nuevo', 'como nuevo', 'buen estado', 'segunda mano', 'usado')
                    ) product ON TRUE
                    WHERE TRUE
                """;
        if (search.category() != null) {
            params.put("category", search.category());
            matches += " AND l.category_id IN (SELECT id FROM category_tree)";
        }
        if (!search.q().isEmpty()) {
            params.put("term", search.q());
            matches += " AND l.search_document @@ " + TEXT_QUERY;
        }
        if (search.seller() != null) {
            params.put("seller", search.seller());
            matches += " AND l.seller_slug = :seller";
        }
        if (search.min() != null) {
            params.put("minPrice", search.min());
            matches += " AND sku.price >= :minPrice";
        }
        if (search.max() != null) {
            params.put("maxPrice", search.max());
            matches += " AND sku.price <= :maxPrice";
        }
        if (search.inStock() != null) {
            params.put("inStock", search.inStock());
            matches += " AND (sku.available_stock > 0) = :inStock";
        }
        if (search.condition() != null) {
            matches += search.condition() == ConditionFilter.nuevo
                    ? " AND product.condition = 'nuevo'"
                    : " AND product.condition <> 'nuevo'";
        }
        for (var attribute : search.attributes().entrySet()) {
            int index = params.size();
            params.put("key" + index, attribute.getKey());
            params.put("value" + index, attribute.getValue());
            matches += " AND jsonb_extract_path_text(sku.attributes, :key" + index + ") = :value" + index;
        }
        matches += ")\n";

        long total = sql.sql(matches + "SELECT count(DISTINCT id) FROM matched")
                .params(params).query(Long.class).single();
        long pages = (total + search.size() - 1) / search.size();
        if (total == 0) {
            return new ListingPage(List.of(), search.page(), search.size(), 0, 0, List.of(), List.of());
        }

        var facets = sql.sql(matches + """
                SELECT attr.key, attr.value, count(DISTINCT m.id) AS count
                FROM matched m CROSS JOIN LATERAL jsonb_each_text(m.attributes) AS attr
                GROUP BY attr.key, attr.value ORDER BY count DESC, attr.key, attr.value LIMIT 40
                """).params(params).query(AttributeFacet.class).list();
        var sellers = sql.sql(matches + """
                SELECT seller_slug AS slug, seller_name AS name, count(DISTINCT id) AS count
                FROM matched GROUP BY seller_slug, seller_name ORDER BY count DESC, seller_slug LIMIT 20
                """).params(params).query(SellerFacet.class).list();

        String order = switch (search.sort()) {
            case relevance -> search.q().isEmpty() ? "" : "ts_rank(l.search_document, " + TEXT_QUERY + ") DESC, ";
            case newest -> "";
            case price_asc -> "result.price ASC, ";
            case price_desc -> "result.price DESC, ";
        };
        params.put("size", search.size());
        params.put("offset", (long) search.page() * search.size());
        var cards = sql.sql(matches + """
                , result AS (
                    SELECT id, COALESCE(min(price) FILTER (WHERE available), min(price)) AS price,
                           bool_or(available) AS available, max(condition) AS condition,
                           CASE WHEN count(*) FILTER (WHERE available) = 1
                               THEN (array_agg(sku_id) FILTER (WHERE available))[1] END AS quick_sku_id
                    FROM matched GROUP BY id
                )
                SELECT l.id, l.slug, l.title, l.category_id, seller.slug AS seller_slug, seller.display_name AS seller_name,
                       cover.thumbnail_url, result.price, result.available, result.condition, result.quick_sku_id
                FROM result JOIN zonda_market.listing l ON l.id = result.id
                JOIN zonda_market.seller_profile seller ON seller.id = l.seller_id
                LEFT JOIN LATERAL (
                    SELECT image.thumbnail_url FROM zonda_market.listing_image image
                    WHERE image.listing_id = l.id AND NOT image.deleted ORDER BY image.position, image.id LIMIT 1
                ) cover ON TRUE
                ORDER BY """ + " " + order + "l.created_at DESC, l.id LIMIT :size OFFSET :offset")
                .params(params).query((rs, row) -> new ListingCardResponse(
                        rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
                        rs.getObject("category_id", UUID.class), rs.getString("seller_slug"), rs.getString("seller_name"),
                        rs.getString("thumbnail_url"), rs.getBigDecimal("price").toPlainString(), rs.getBoolean("available"),
                        rs.getString("condition"), rs.getObject("quick_sku_id", UUID.class)))
                .list();

        return new ListingPage(cards, search.page(), search.size(), total, pages, facets, sellers);
    }
}
