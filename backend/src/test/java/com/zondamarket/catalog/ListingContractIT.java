package com.zondamarket.catalog;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class ListingContractIT {
    private static final String LISTINGS = "/api/v1/seller/listings";
    private static final String CLOTHING = "e2010000-0000-4000-8000-000000000001";
    private static final String TECHNOLOGY = "e0020000-0000-4000-8000-000000000001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;

    @Test
    void createsReopensAndEditsTheDraftAndItsTwoVariants() throws Exception {
        String seller = seller();
        mvc.perform(get("/api/v1/categories/" + CLOTHING))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attributeSchema.talle").isArray());
        var categoryResult = mvc.perform(auth(get("/api/v1/seller/listing-categories"), seller))
                .andExpect(status().isOk()).andReturn();
        List<String> categoryIds = JsonPath.read(categoryResult.getResponse().getContentAsString(), "$[*].id");
        assertTrue(categoryIds.contains(CLOTHING));

        String path = newDraft(seller);
        String skuPath = addSku(path, seller, "remera-negra", "Negro", "M");
        addSku(path, seller, "remera-azul", "Azul", "L");

        mvc.perform(auth(get(LISTINGS), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Remera ficticia"));
        mvc.perform(auth(get(path), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skus.length()").value(2))
                .andExpect(jsonPath("$.specs.material").value("algodón"));
        mvc.perform(auth(patch(path), seller).content("""
                {"expectedVersion":0,"title":"Remera actualizada"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(auth(patch(skuPath), seller).content("""
                {"expectedVersion":0,"attributes":{"talle":"S","color":"Blanco"},"price":"12990.95"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.code").value("REMERA-NEGRA"))
                .andExpect(jsonPath("$.price").value("12990.95"))
                .andExpect(jsonPath("$.availableStock").value(7));

        mvc.perform(auth(get(path), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Remera actualizada"))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.description").value("Producto ficticio"))
                .andExpect(jsonPath("$.skus[0].attributes.talle").value("S"))
                .andExpect(jsonPath("$.skus[1].attributes.color").value("Azul"));
        assertEquals(0, sql.sql("select reserved_stock from zonda_market.listing_sku where code = 'REMERA-NEGRA'")
                .query(Integer.class).single());
    }

    @Test
    void rejectsInvalidSchemasJsonMoneyDuplicatesAndUnauthorizedAccess() throws Exception {
        String owner = seller();
        String stranger = seller();
        String buyer = register();
        mvc.perform(get(LISTINGS)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(LISTINGS), buyer)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_REQUIRED"));
        mvc.perform(auth(post(LISTINGS), buyer).content(draft(CLOTHING)))
                .andExpect(status().isForbidden());
        String path = newDraft(owner);
        String skuPath = addSku(path, owner, "original", "Negro", "M");

        mvc.perform(auth(get(LISTINGS), stranger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(get(path), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(patch(path), stranger).content("{\"expectedVersion\":0,\"title\":\"Ajena\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(post(path + "/skus"), stranger).content(sku("otro", "Rojo", "L")))
                .andExpect(status().isNotFound());
        mvc.perform(auth(patch(skuPath), stranger).content("{\"expectedVersion\":0,\"price\":10}"))
                .andExpect(status().isNotFound());

        mvc.perform(auth(post(path + "/skus"), owner).content(sku("ORIGINAL", "Azul", "L")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_CODE_IN_USE"));
        mvc.perform(auth(post(path + "/skus"), owner).content("""
                {"code":"otra-combinacion","attributes":{"talle":"M","color":"Negro"},"price":50,"initialStock":0}
                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_COMBINATION_IN_USE"));
        mvc.perform(auth(post(path + "/skus"), owner).content(sku("enum-invalido", "Verde", "M")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_ATTRIBUTES_INVALID"));
        mvc.perform(auth(post(path + "/skus"), owner).content(sku("precio-invalido", "Rojo", "M").replace("12990.50", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post(path + "/skus"), owner).content(sku("precision-invalida", "Rojo", "M").replace("12990.50", "1.001")))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post(path + "/skus"), owner).content("""
                {"code":"stock-fraccionario","attributes":{"color":"Rojo","talle":"M"},"price":50,"initialStock":7.5}
                """)).andExpect(status().isBadRequest());
        mvc.perform(auth(patch(skuPath), owner).content("""
                {"expectedVersion":0.5,"price":10}
                """)).andExpect(status().isBadRequest());
        mvc.perform(auth(patch(skuPath), owner).content("{\"expectedVersion\":0,\"initialStock\":1,\"code\":\"NUEVO\"}"))
                .andExpect(status().isBadRequest());
        String otherPath = newDraft(owner);
        String otherSku = addSku(otherPath, owner, "otro-original", "Negro", "M");
        mvc.perform(auth(patch(path + otherSku.substring(otherSku.indexOf("/skus/"))), owner)
                .content("{\"expectedVersion\":0,\"price\":10}")).andExpect(status().isNotFound());

        mvc.perform(auth(patch(path), owner).content("""
                {"expectedVersion":0,"categoryId":"%s"}
                """.formatted(TECHNOLOGY))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SKUS_INCOMPATIBLE"));
        mvc.perform(auth(post(LISTINGS), owner).content(draft(CLOTHING).replace("algodón", "ñ".repeat(4500))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPECS_TOO_LARGE"));

        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        sql.sql("""
                insert into zonda_market.category (id, name, slug, parent_id, created_at, updated_at)
                values (:parent, 'Raíz de prueba', :parentSlug, null, now(), now()),
                       (:child, 'Hoja de prueba', :childSlug, :parent, now(), now())
                """).param("parent", parent).param("child", child).param("parentSlug", parent.toString())
                .param("childSlug", child.toString()).update();
        mvc.perform(auth(post(LISTINGS), owner).content(draft(parent.toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_LEAF"));

        sql.sql("update zonda_market.listing_sku set deleted = true where code = 'ORIGINAL'").update();
        mvc.perform(auth(post(otherPath + "/skus"), owner).content(sku("original", "Blanco", "L")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_CODE_IN_USE"));
    }

    @Test
    void onlyOneWriterWithTheSameVersionCanUpdateEachResource() throws Exception {
        String seller = seller();
        String path = newDraft(seller);
        String skuPath = addSku(path, seller, "carrera", "Azul", "M");
        race(path, seller, "{\"expectedVersion\":0,\"title\":\"Primera\"}", "{\"expectedVersion\":0,\"title\":\"Segunda\"}");
        race(skuPath, seller, "{\"expectedVersion\":0,\"price\":100}", "{\"expectedVersion\":0,\"price\":200}");
        mvc.perform(auth(get(path), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.skus[0].version").value(1))
                .andExpect(jsonPath("$.skus[0].availableStock").value(7));
    }

    private void race(String path, String seller, String first, String second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var jobs = List.of(first, second).stream().map(body -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("La edición concurrente no se inició.");
                return mvc.perform(auth(patch(path), seller).content(body)).andReturn();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(jobs.get(0).get(10, TimeUnit.SECONDS), jobs.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), results.stream().map(result -> result.getResponse().getStatus()).sorted().toList());
            var conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertEquals("VERSION_CONFLICT", field(conflict, "code"));
        }
    }

    private String newDraft(String seller) throws Exception {
        var result = mvc.perform(auth(post(LISTINGS), seller).content(draft(CLOTHING)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        return LISTINGS + "/" + field(result, "id");
    }

    private String addSku(String path, String seller, String code, String color, String size) throws Exception {
        var result = mvc.perform(auth(post(path + "/skus"), seller).content(sku(code, color, size)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0)).andReturn();
        return path + "/skus/" + field(result, "id");
    }

    private String seller() throws Exception {
        String token = register();
        var account = mvc.perform(auth(get("/api/v1/me"), token)).andExpect(status().isOk()).andReturn();
        sql.sql("update zonda_market.user_account set email_verified = true where id = :id")
                .param("id", UUID.fromString(field(account, "id"))).update();
        mvc.perform(auth(post("/api/v1/me/seller-profile"), token).content("""
                {"displayName":"Taller ficticio","type":"PERSONAL","slug":"taller-%s"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated());
        return token;
    }

    private String register() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s@example.test","password":"contraseña-solo-de-prueba"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return field(result, "accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private static String field(MvcResult result, String name) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + name);
    }

    private static String draft(String category) {
        return """
                {"categoryId":"%s","title":" Remera ficticia ","description":"Producto ficticio","specs":{"material":"algodón"}}
                """.formatted(category);
    }

    private static String sku(String code, String color, String size) {
        return """
                {"code":"%s","attributes":{"color":"%s","talle":"%s"},"price":12990.50,"initialStock":7}
                """.formatted(code, color, size);
    }
}
