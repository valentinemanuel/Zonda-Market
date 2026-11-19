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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class StockContractIT {
    private static final String SKUS = "/api/v1/seller/skus/";
    private static final String LISTINGS = "/api/v1/seller/listings";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;

    @Test
    void adjustsReplaysAndPagesHistoryWithoutChangingReservedUnits() throws Exception {
        var owner = account(true);
        String id = sku(owner);
        sql.sql("update zonda_market.listing_sku set reserved_stock = 4 where id = :id")
                .param("id", UUID.fromString(id)).update();

        String add = adjustment(5, "  Reposición  ", 0);
        MvcResult first = adjust(owner, id, "agregar", add).andExpect(status().isOk())
                .andExpect(jsonPath("$.beforeStock").value(7)).andExpect(jsonPath("$.afterStock").value(12))
                .andExpect(jsonPath("$.reason").value("Reposición")).andExpect(jsonPath("$.actorId").value(owner.id()))
                .andExpect(jsonPath("$.version").value(1)).andReturn();
        assertEquals(first.getResponse().getHeader("X-Trace-Id"), field(first, "traceId"));
        adjust(owner, id, "quitar", adjustment(-2, "Corrección", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.beforeStock").value(12)).andExpect(jsonPath("$.afterStock").value(10))
                .andExpect(jsonPath("$.version").value(2));
        var replay = adjust(owner, id, "agregar", adjustment(5, "Reposición", 0))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        adjust(owner, id, "agregar", adjustment(5, "Motivo diferente", 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        adjust(owner, id, "agregar", adjustment(5, "Reposición", 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        adjust(owner, id, "baja-excesiva", adjustment(-11, "Baja excesiva", 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        adjust(owner, id, "limite", adjustment(Integer.MAX_VALUE, "Demasiadas unidades", 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));

        mvc.perform(auth(get(SKUS + id + "/stock-movements?page=0&size=1"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].delta").value(-2));
        mvc.perform(auth(get(SKUS + id + "/stock-movements?page=1&size=1"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(field(first, "id")))
                .andExpect(jsonPath("$.items[0].idempotencyKey").doesNotExist());
        mvc.perform(auth(get(SKUS + id + "/stock-movements?page=2&size=1"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertSku(id, 10, 4, 2);
    }

    @Test
    void checksPermissionsVersionAndValidIntegerInputs() throws Exception {
        var owner = account(true);
        var stranger = account(true);
        var buyer = account(false);
        String id = sku(owner);
        mvc.perform(get(SKUS + id + "/stock-movements")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(SKUS + id + "/stock-movements"), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(get(SKUS + id + "/stock-movements"), buyer)).andExpect(status().isForbidden());
        adjust(stranger, id, "ajeno", adjustment(5, "Ajeno", 0)).andExpect(status().isNotFound());
        adjust(buyer, id, "comprador", adjustment(5, "Comprador", 0)).andExpect(status().isForbidden());
        adjust(owner, id, "obsoleta", adjustment(1, "Versión antigua", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        adjust(owner, id, "cero", adjustment(0, "Cero", 0)).andExpect(status().isBadRequest());
        adjust(owner, id, "fraccion", "{\"delta\":1.5,\"reason\":\"Fracción\",\"expectedVersion\":0}")
                .andExpect(status().isBadRequest());
        adjust(owner, id, "motivo", adjustment(1, "   ", 0)).andExpect(status().isBadRequest());
        adjust(owner, id, "invalida con espacios", adjustment(1, "Reposición", 0)).andExpect(status().isBadRequest());
        mvc.perform(auth(post(SKUS + id + "/stock-adjustments"), owner).content(adjustment(1, "Sin clave", 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(SKUS + id + "/stock-movements?page=-1&size=101"), owner)).andExpect(status().isBadRequest());
        assertSku(id, 7, 0, 0);
    }

    @Test
    void serializesCompetingUpdatesAndReplaysConcurrentDuplicates() throws Exception {
        var owner = account(true);
        String id = sku(owner);
        String add = adjustment(5, "Ingreso", 0);
        var duplicate = race(owner, id, "misma", add, "misma", add);
        assertEquals(List.of(200, 200), statuses(duplicate));
        assertEquals(field(duplicate.get(0), "id"), field(duplicate.get(1), "id"));
        assertSku(id, 12, 0, 1);

        var competing = race(owner, id, "primera", adjustment(-2, "Conteo uno", 1),
                "segunda", adjustment(-2, "Conteo dos", 1));
        assertEquals(List.of(200, 409), statuses(competing));
        var conflict = competing.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertEquals("VERSION_CONFLICT", field(conflict, "code"));
        assertSku(id, 10, 0, 2);
        mvc.perform(auth(get(SKUS + id + "/stock-movements"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void rollsBackStockAndAllowsTheSameKeyAfterTheAuditInsertFails() throws Exception {
        var owner = account(true);
        String id = sku(owner);
        String command = adjustment(5, "Fallo transaccional de prueba", 0);
        sql.sql("""
                alter table zonda_market.stock_movement add constraint fixture_audit_insert
                check (reason <> 'Fallo transaccional de prueba')
                """).update();
        try {
            adjust(owner, id, "recuperable", command).andExpect(status().isInternalServerError());
            assertSku(id, 7, 0, 0);
        } finally {
            sql.sql("alter table zonda_market.stock_movement drop constraint fixture_audit_insert").update();
        }
        adjust(owner, id, "recuperable", command).andExpect(status().isOk())
                .andExpect(jsonPath("$.afterStock").value(12));
        assertSku(id, 12, 0, 1);
    }

    private List<MvcResult> race(Account owner, String id, String keyOne, String one, String keyTwo, String two) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> waitAndAdjust(ready, start, owner, id, keyOne, one));
            var second = executor.submit(() -> waitAndAdjust(ready, start, owner, id, keyTwo, two));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private MvcResult waitAndAdjust(CountDownLatch ready, CountDownLatch start, Account owner, String id, String key, String body)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("No arrancó la prueba concurrente.");
        return adjust(owner, id, key, body).andReturn();
    }

    private static List<Integer> statuses(List<MvcResult> results) {
        return results.stream().map(result -> result.getResponse().getStatus()).sorted().toList();
    }

    private void assertSku(String id, int available, int reserved, long version) {
        var actual = sql.sql("select available_stock, reserved_stock, version from zonda_market.listing_sku where id = :id")
                .param("id", UUID.fromString(id)).query((row, number) ->
                        List.of(row.getLong("available_stock"), row.getLong("reserved_stock"), row.getLong("version"))).single();
        assertEquals(List.of((long) available, (long) reserved, version), actual);
    }

    private ResultActions adjust(Account owner, String id, String key, String body) throws Exception {
        return mvc.perform(auth(post(SKUS + id + "/stock-adjustments"), owner).header("Idempotency-Key", key).content(body));
    }

    private String sku(Account owner) throws Exception {
        var listing = mvc.perform(auth(post(LISTINGS), owner).content("""
                {"categoryId":"e2010000-0000-4000-8000-000000000001","title":"Stock ficticio","description":"Prueba","specs":{}}
                """)).andExpect(status().isCreated()).andReturn();
        var sku = mvc.perform(auth(post(LISTINGS + "/" + field(listing, "id") + "/skus"), owner).content("""
                {"code":"SKU-%s","attributes":{"color":"Azul","talle":"M"},"price":100,"initialStock":7}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return field(sku, "id");
    }

    private Account account(boolean seller) throws Exception {
        var session = mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s@example.test","password":"contraseña-ficticia-e203"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        String token = field(session, "accessToken");
        var self = mvc.perform(auth(get("/api/v1/me"), new Account("", token))).andExpect(status().isOk()).andReturn();
        var account = new Account(field(self, "id"), token);
        if (seller) {
            sql.sql("update zonda_market.user_account set email_verified = true where id = :id")
                    .param("id", UUID.fromString(account.id())).update();
            mvc.perform(auth(post("/api/v1/me/seller-profile"), account).content("""
                    {"type":"PERSONAL","displayName":"Vendedor ficticio","slug":"stock-%s"}
                    """.formatted(UUID.randomUUID()))).andExpect(status().isCreated());
        }
        return account;
    }

    private static String adjustment(int delta, String reason, long version) {
        return """
                {"delta":%d,"reason":"%s","expectedVersion":%d}
                """.formatted(delta, reason, version);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Account account) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token()).contentType(MediaType.APPLICATION_JSON);
    }

    private static String field(MvcResult result, String name) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + name);
    }

    private record Account(String id, String token) {}
}
