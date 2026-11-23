package com.zondamarket.checkout;

import com.zondamarket.checkout.service.LocalPaymentPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class CategoryAdminContractIT extends CheckoutContractFixtures {
    static final String CATEGORIES = "/api/v1/admin/categories";
    static final String SCHEMA = """
            {"color":["Azul","Rojo"],"talle":["M"]}
            """;

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void restrictsWritesAndValidatesDepthReferencesSlugAndSchema() throws Exception {
        var admin = admin();
        var seller = account(true);
        product(seller, "Permisos categorías", "100.00", "0", 2);
        String payload = input("admin-a", null, 0, "{}", null);
        mvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(CATEGORIES), seller)).andExpect(status().isForbidden());
        mvc.perform(auth(post(CATEGORIES), seller).content(payload)).andExpect(status().isForbidden());

        var root = create(admin, "admin-a", null, 0, "{}");
        edit(seller, root, "admin-a", null, 0, "{}", 0).andExpect(status().isForbidden());
        archive(seller, root, 0).andExpect(status().isForbidden());
        mvc.perform(auth(post(CATEGORIES + "/" + root + "/archive"), admin).content("{\"expectedVersion\":0.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(CATEGORIES), admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        edit(admin, root, "admin-a", root, 0, "{}", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
        edit(admin, root, "admin-a", null, 10001, "{}", 0).andExpect(status().isBadRequest());
        edit(admin, root, "admin-a", null, 0, "{\"color\":[5]}", 0).andExpect(status().isBadRequest());
        edit(admin, root, "admin-a", null, 0, "{\"color\":[\"Azul\",\"Azul\"]}", 0).andExpect(status().isBadRequest());
        mvc.perform(auth(post(CATEGORIES), admin).content(payload)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SLUG_IN_USE"));

        var second = create(admin, "admin-b", root, 0, "{}");
        var third = create(admin, "admin-c", second, 0, "{}");
        var fourth = create(admin, "admin-d", third, 0, "{}");
        mvc.perform(auth(post(CATEGORIES), admin).content(input("admin-e", fourth, 0, "{}", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_DEPTH"));
        var other = create(admin, "admin-f", null, 0, "{}");
        edit(admin, second, "admin-b", third, 0, "{}", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
        edit(admin, root, "admin-a", other, 0, "{}", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_DEPTH"));
        archive(admin, root, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        edit(admin, fourth, "admin-d", CATEGORY, 0, "{}", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
        archive(admin, CATEGORY, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
        edit(admin, CATEGORY, "indumentaria", null, 0, "{}", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SKUS_INCOMPATIBLE"));
        edit(admin, CATEGORY, "indumentaria", null, 0, SCHEMA, 0).andExpect(status().isOk());

        edit(admin, fourth, "admin-d", other, 0, "{}", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(other.toString()))
                .andExpect(jsonPath("$.version").value(1));
        archive(admin, fourth, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        archive(admin, fourth, 1).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        mvc.perform(get("/api/v1/categories/" + fourth)).andExpect(status().isNotFound());
        mvc.perform(auth(post(CATEGORIES), admin).content(input("admin-d", null, 0, "{}", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_SLUG_IN_USE"));
        edit(admin, third, "admin-c", fourth, 0, "{}", 0).andExpect(status().isBadRequest());
    }

    @Test
    void preventsCyclesUnderConcurrentMoves() throws Exception {
        var first = admin();
        var second = admin();
        var a = create(first, "ciclo-a", null, 0, "{}");
        var b = create(second, "ciclo-b", null, 0, "{}");
        var starting = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var moveA = workers.submit(() -> {
                starting.await(5, TimeUnit.SECONDS);
                return edit(first, a, "ciclo-a", b, 0, "{}", 0);
            });
            var moveB = workers.submit(() -> {
                starting.await(5, TimeUnit.SECONDS);
                return edit(second, b, "ciclo-b", a, 0, "{}", 0);
            });
            var responses = List.of(moveA.get(10, TimeUnit.SECONDS), moveB.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), responses.stream().map(result -> result.andReturn().getResponse().getStatus())
                    .sorted().toList());
            for (var result : responses) {
                if (result.andReturn().getResponse().getStatus() == 409) {
                    result.andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
                }
            }
        }
    }

    @Test
    void preservesFrozenRatesAndNetAndRoundsPerLineForTheSameSeller() throws Exception {
        var admin = admin();
        var seller = account(true);
        uuidForSeller(seller, "Tienda de dos categorías");
        var categoryA = create(admin, "primera-categoria", null, 0, SCHEMA);
        var skuA = listingProduct(seller, categoryA, "Primera categoría", "10.04", 5, "{\"color\":\"Azul\",\"talle\":\"M\"}");
        var categoryB = create(admin, "segunda-categoria", null, 2500, "{}");
        var skuB = listingProduct(seller, categoryB, "Segunda categoría", "20.02", 5, "{}");
        edit(admin, categoryA, "primera-categoria", null, 1250, SCHEMA, 0).andExpect(status().isOk());
        var buyer = account(true);
        var otherBuyer = account(true);
        var address = address(buyer);
        var otherAddress = address(otherBuyer);
        for (var account : List.of(buyer, otherBuyer)) {
            add(account, skuA, 1, 0);
            add(account, skuB, 1, 1);
        }

        var oldQuote = uuid(quote(buyer, address, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].items[0].commission").value("1.26"))
                .andExpect(jsonPath("$.groups[0].items[1].commission").value("5.01"))
                .andExpect(jsonPath("$.groups[0].commission").value("6.27")).andReturn());
        var order = uuid(confirm(buyer, oldQuote, "e602-old").andExpect(status().isOk()).andReturn());
        var pendingQuote = uuid(quote(otherBuyer, otherAddress, 2).andExpect(status().isOk()).andReturn());

        edit(admin, categoryA, "primera-categoria", null, 5000, SCHEMA, 1).andExpect(status().isOk());
        confirm(otherBuyer, pendingQuote, "e602-new").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));
        assertStock(skuA, 4, 1);
        confirm(buyer, oldQuote, "e602-old").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.toString()))
                .andExpect(jsonPath("$.subOrders[0].group.commission").value("6.27"));

        var newQuote = uuid(quote(otherBuyer, otherAddress, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].commission").value("10.03")).andReturn());
        var newOrder = uuid(confirm(otherBuyer, newQuote, "e602-new").andExpect(status().isOk()).andReturn());
        approve(buyer, order);
        approve(otherBuyer, newOrder);
        assertEquals("23.79", net(order));
        assertEquals("20.03", net(newOrder));
        assertEquals(List.of(0, 1250, 5000), sql.sql("""
                SELECT commission_bps FROM zonda_market.category_commission_period
                WHERE category_id = :id ORDER BY category_version
                """).param("id", categoryA).query(Integer.class).list());
        assertEquals(2, sql.sql("""
                SELECT count(*) FROM zonda_market.category_commission_period old
                JOIN zonda_market.category_commission_period next
                  ON old.category_id = next.category_id AND old.valid_until = next.valid_from
                  AND next.category_version > old.category_version
                WHERE old.category_id = :id
                """).param("id", categoryA).query(Integer.class).single());
    }

    private String net(UUID order) {
        return sql.sql("""
                SELECT entry.seller_net FROM zonda_market.checkout_sub_order_settlement entry
                JOIN zonda_market.checkout_sub_order sub ON sub.id = entry.sub_order_id WHERE sub.order_id = :id
                """).param("id", order).query(java.math.BigDecimal.class).single().toPlainString();
    }

    private void approve(Account buyer, UUID order) throws Exception {
        mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + order + "/payment-outcome"), buyer).content("""
                {"reference":"%s","outcome":"APPROVED"}
                """.formatted(LocalPaymentPort.reference(order)))).andExpect(status().isOk());
    }

    private Account admin() throws Exception {
        var admin = account(true);
        sql.sql("UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE id = :id").param("id", admin.id()).update();
        return admin;
    }

    private UUID create(Account admin, String slug, UUID parent, int rate, String schema) throws Exception {
        return uuid(mvc.perform(auth(post(CATEGORIES), admin).content(input(slug, parent, rate, schema, null)))
                .andExpect(status().isCreated()).andReturn());
    }

    private ResultActions edit(Account actor, UUID id, String slug, UUID parent, int rate, String schema, long version) throws Exception {
        return mvc.perform(auth(patch(CATEGORIES + "/" + id), actor).content(input(slug, parent, rate, schema, version)));
    }

    private ResultActions archive(Account actor, UUID id, long version) throws Exception {
        return mvc.perform(auth(post(CATEGORIES + "/" + id + "/archive"), actor)
                .content("{\"expectedVersion\":%d}".formatted(version)));
    }

    private static String input(String slug, UUID parent, int rate, String schema, Long version) {
        String parentJson = parent == null ? "null" : "\"" + parent + "\"";
        return """
                {"parentId":%s,"name":"%s","slug":"%s","commissionBps":%d,"attributeSchema":%s,"expectedVersion":%s}
                """.formatted(parentJson, slug, slug, rate, schema, version);
    }
}
