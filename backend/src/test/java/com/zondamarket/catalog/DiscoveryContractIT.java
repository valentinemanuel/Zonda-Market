package com.zondamarket.catalog;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class DiscoveryContractIT {
    private static final String LISTINGS = "/api/v1/listings";
    private static final String QUEUE = "/api/v1/seller/questions";
    private static final UUID CLOTHING = UUID.fromString("e2010000-0000-4000-8000-000000000001");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;

    @Test
    void matchesSpanishTermsAndFiltersTheSameVariant() throws Exception {
        Seller seller = seller();
        UUID parent = UUID.randomUUID();
        sql.sql("""
                INSERT INTO zonda_market.category (id, name, slug, created_at, updated_at)
                VALUES (:id, 'Moda ficticia', 'moda-ficticia', now(), now())
                """).param("id", parent).update();
        sql.sql("UPDATE zonda_market.category SET parent_id = :parent WHERE id = :id")
                .param("id", CLOTHING).param("parent", parent).update();

        UUID first = listing(seller.id(), "Remeras naranjas · segunda mano");
        UUID second = listing(seller.id(), "Remeras violetas");
        try {
            sql.sql("""
                    UPDATE zonda_market.listing SET specs = '{"Condición": " Nuevo "}'::jsonb WHERE id = :id
                    """).param("id", second).update();
            sku(first, "Azul", "S", 50, 0);
            sku(first, "Rojo", "M", 120, 4);
            sku(second, "Azul", "M", 90, 3);

            mvc.perform(get("/api/v1/categories")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.category.slug == 'moda-ficticia')].children[0].category.slug")
                            .value("indumentaria"));
            mvc.perform(get(LISTINGS).param("q", "remera").param("category", parent.toString())
                            .param("seller", seller.slug()).param("size", "1").param("sort", "price_asc"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.content[0].id").value(second.toString()))
                    .andExpect(jsonPath("$.content[0].price").value("90.00"))
                    .andExpect(jsonPath("$.content[0].quickSkuId").isNotEmpty());
            mvc.perform(get(LISTINGS).param("seller", seller.slug()).param("condition", "usado").param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.content[0].condition").value("segunda mano"));
            mvc.perform(get(LISTINGS).param("seller", seller.slug()).param("condition", "nuevo"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].condition").value("nuevo"));
            mvc.perform(get(LISTINGS).param("condition", "desconocido")).andExpect(status().isBadRequest());
            mvc.perform(get(LISTINGS).param("q", "remera").param("seller", seller.slug())
                            .param("page", "1").param("size", "1").param("sort", "price_asc"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(first.toString()));

            mvc.perform(get(LISTINGS).param("q", "remeras").param("min", "85").param("max", "100")
                            .param("attr", "color:Azul", "talle:M").param("inStock", "true").param("seller", seller.slug()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(second.toString()))
                    .andExpect(jsonPath("$.facets[?(@.key == 'color')].value").value("Azul"))
                    .andExpect(jsonPath("$.sellers[0].slug").value(seller.slug()));
            mvc.perform(get(LISTINGS).param("q", "remera").param("max", "80")
                            .param("attr", "color:Azul").param("inStock", "true").param("seller", seller.slug()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content").isEmpty());
            mvc.perform(get(LISTINGS).param("min", "100").param("max", "80")).andExpect(status().isBadRequest());
            mvc.perform(get(LISTINGS).param("attr", "color:Azul", "color:Rojo")).andExpect(status().isBadRequest());

            sql.sql("UPDATE zonda_market.seller_profile SET deleted = true WHERE id = :id")
                    .param("id", seller.id()).update();
            mvc.perform(get(LISTINGS).param("seller", seller.slug()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        } finally {
            sql.sql("UPDATE zonda_market.category SET parent_id = NULL WHERE id = :id").param("id", CLOTHING).update();
        }
    }

    @Test
    void keepsAPlainTextConversationPublicAndChecksOwnershipAndVisibility() throws Exception {
        Seller owner = seller();
        Seller other = seller();
        Account buyer = register();
        UUID listing = listing(owner.id(), "Campera ficticia");
        sku(listing, "Azul", "M", 100, 2);
        String questions = LISTINGS + "/" + listing + "/questions";

        mvc.perform(get(questions)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(post(questions).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"¿Tiene cierre?\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(auth(post(questions), buyer.token()).content("{\"text\":\"   \"}")).andExpect(status().isBadRequest());
        String id = field(mvc.perform(auth(post(questions), buyer.token()).content("{\"text\":\"¿Tiene cierre?\"}"))
                .andExpect(status().isCreated()).andReturn(), "id");
        String answer = QUEUE + "/" + id + "/answer";

        mvc.perform(auth(get(QUEUE), buyer.token())).andExpect(status().isForbidden());
        mvc.perform(auth(get(QUEUE), other.token())).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(auth(get(QUEUE), owner.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id));
        String text = "Tiene cierre. <b>Texto literal</b>";
        String body = "{\"text\":\"" + text + "\"}";
        mvc.perform(put(answer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(auth(put(answer), other.token()).content(body)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
        mvc.perform(auth(put(answer), buyer.token()).content(body)).andExpect(status().isNotFound());
        var answered = mvc.perform(auth(put(answer), owner.token()).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(text)).andReturn();
        String answeredAt = field(answered, "answeredAt");
        mvc.perform(auth(put(answer), owner.token()).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredAt").value(answeredAt));
        mvc.perform(auth(put(answer), owner.token()).content("{\"text\":\"Otro texto\"}")).andExpect(status().isConflict());
        mvc.perform(get(questions)).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].answer").value(text))
                .andExpect(jsonPath("$.content[0].authorId").doesNotExist());
        mvc.perform(auth(get(QUEUE), owner.token())).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(auth(post("/api/v1/seller/listings/" + listing + "/archive"), owner.token()))
                .andExpect(status().isOk());
        mvc.perform(get(LISTINGS).param("seller", owner.slug()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(questions)).andExpect(status().isNotFound());
        mvc.perform(auth(post(questions), buyer.token()).content("{\"text\":\"¿Disponible?\"}")).andExpect(status().isNotFound());
        mvc.perform(auth(put(answer), owner.token()).content(body)).andExpect(status().isNotFound());
    }

    @Test
    void sharesTheQuestionBudgetAcrossListingsAndConcurrentRequests() throws Exception {
        Seller owner = seller();
        Account buyer = register();
        UUID firstId = listing(owner.id(), "Primera publicación");
        UUID secondId = listing(owner.id(), "Segunda publicación");
        sku(firstId, "Azul", "M", 100, 1);
        sku(secondId, "Azul", "M", 100, 1);
        String first = LISTINGS + "/" + firstId + "/questions";
        String second = LISTINGS + "/" + secondId + "/questions";
        String body = "{\"text\":\"¿Está disponible?\"}";
        for (int index = 0; index < 9; index++) {
            mvc.perform(auth(post(first), buyer.token()).content(body)).andExpect(status().isCreated());
        }
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = threads.submit(() -> mvc.perform(auth(post(first), buyer.token()).content(body)).andReturn());
            var right = threads.submit(() -> mvc.perform(auth(post(second), buyer.token()).content(body)).andReturn());
            var results = java.util.List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(result -> result.getResponse().getStatus())).containsExactlyInAnyOrder(201, 429);
            assertThat(field(results.stream().filter(result -> result.getResponse().getStatus() == 429)
                    .findFirst().orElseThrow(), "code")).isEqualTo("QUESTION_RATE_LIMIT");
        }
    }

    @Test
    void usesTheGinIndexForASelectiveTermWithProductionPlannerSettings() throws Exception {
        Seller seller = seller();
        UUID rare = listing(seller.id(), "Telescopios ficticios");
        sku(rare, "Negro", "M", 150, 1);
        sql.sql("""
                WITH listings AS (
                    INSERT INTO zonda_market.listing (id, seller_id, category_id, slug, status, title, description,
                                                     specs, created_at, updated_at)
                    SELECT gen_random_uuid(), :seller, :category, 'fixture-catalogo-' || n, 'ACTIVA',
                           (ARRAY['Camperas', 'Pantalones', 'Polleras', 'Remeras'])[1 + n % 4] || ' modelo ' || n,
                           'Prenda de algodón en talles variados', '{}', now(), now()
                    FROM generate_series(1, 12000) AS n RETURNING id
                ), variants AS (
                    INSERT INTO zonda_market.listing_sku (id, listing_id, code, attributes, price, available_stock,
                                                         created_at, updated_at)
                    SELECT gen_random_uuid(), id, id::text, '{"color":"Azul","talle":"M"}', 100, 5, now(), now() FROM listings
                )
                INSERT INTO zonda_market.listing_image (id, listing_id, provider, public_id, url, thumbnail_url, position,
                                                       created_at, updated_at)
                SELECT gen_random_uuid(), id, 'local', gen_random_uuid(), '/fixture.png', '/fixture.png', 0, now(), now()
                FROM listings
                """).param("seller", seller.id()).param("category", CLOTHING).update();
        sql.sql("VACUUM (ANALYZE) zonda_market.listing, zonda_market.listing_sku, zonda_market.listing_image").update();

        String plan = String.join("\n", sql.sql("""
                EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
                SELECT DISTINCT l.id FROM zonda_market.public_listing l
                JOIN zonda_market.listing_sku sku ON sku.listing_id = l.id AND NOT sku.deleted
                WHERE l.search_document @@ plainto_tsquery('spanish', 'telescopio')
                """).query(String.class).list());
        System.out.println("E2-04. Plan con 12 001 publicaciones y variantes:\n" + plan);
        assertThat(plan).contains("Bitmap Index Scan on listing_search_gin");
        mvc.perform(get(LISTINGS).param("q", "telescopio").param("seller", seller.slug()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(rare.toString()));
    }

    private Account register() throws Exception {
        var registered = mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s@example.test","password":"contraseña-solo-de-prueba"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        String token = field(registered, "accessToken");
        var me = mvc.perform(auth(get("/api/v1/me"), token)).andExpect(status().isOk()).andReturn();
        return new Account(UUID.fromString(field(me, "id")), token);
    }

    private Seller seller() throws Exception {
        Account account = register();
        sql.sql("UPDATE zonda_market.user_account SET email_verified = true WHERE id = :id")
                .param("id", account.id()).update();
        String slug = "catalogo-" + UUID.randomUUID();
        mvc.perform(auth(post("/api/v1/me/seller-profile"), account.token()).content("""
                {"displayName":"Taller ficticio","type":"PERSONAL","slug":"%s"}
                """.formatted(slug))).andExpect(status().isCreated());
        UUID seller = sql.sql("SELECT id FROM zonda_market.seller_profile WHERE account_id = :id")
                .param("id", account.id()).query(UUID.class).single();
        return new Seller(seller, slug, account.token());
    }

    private UUID listing(UUID seller, String title) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                INSERT INTO zonda_market.listing (id, seller_id, category_id, slug, status, title, description,
                                                 specs, created_at, updated_at)
                VALUES (:id, :seller, :category, :slug, 'ACTIVA', :title, 'Descripción de prueba', '{}', now(), now())
                """).param("id", id).param("seller", seller).param("category", CLOTHING)
                .param("slug", "fixture-" + id).param("title", title).update();
        sql.sql("""
                INSERT INTO zonda_market.listing_image (id, listing_id, provider, public_id, url, thumbnail_url, position,
                                                       created_at, updated_at)
                VALUES (gen_random_uuid(), :id, 'local', gen_random_uuid(), '/fixture.png', '/fixture.png', 0, now(), now())
                """).param("id", id).update();
        return id;
    }

    private void sku(UUID listing, String color, String size, int price, int units) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                INSERT INTO zonda_market.listing_sku (id, listing_id, code, attributes, price, available_stock,
                                                     created_at, updated_at)
                VALUES (:id, :listing, :code, jsonb_build_object('color', CAST(:color AS text), 'talle', CAST(:size AS text)),
                        :price, :stock, now(), now())
                """).param("id", id).param("listing", listing).param("code", id.toString())
                .param("color", color).param("size", size).param("price", price).param("stock", units).update();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String field(MvcResult result, String key) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + key);
    }

    private record Account(UUID id, String token) {}
    private record Seller(UUID id, String slug, String token) {}
}
