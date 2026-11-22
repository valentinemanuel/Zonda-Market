package com.zondamarket.checkout;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ModerationContractIT extends CheckoutContractFixtures {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void serializesConcurrentModerationAndRejectsAQuoteWhenVisibilityChanges() throws Exception {
        var admin = account(true);
        var peerAdmin = account(true);
        var seller = account(true);
        var buyer = account(true);
        sql.sql("UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE id IN (:admins)")
                .param("admins", List.of(admin.id(), peerAdmin.id())).update();
        var sku = product(seller, "Moderación", "100.00", "0.00", 5);
        var listing = sql.sql("SELECT listing_id FROM zonda_market.listing_sku WHERE id = :sku")
                .param("sku", sku).query(UUID.class).single();
        var version = sql.sql("SELECT version FROM zonda_market.listing WHERE id = :id")
                .param("id", listing).query(Long.class).single();
        String path = "/api/v1/admin/listings/" + listing;
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());

        mvc.perform(get("/api/v1/admin/listings")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/v1/admin/listings"), seller)).andExpect(status().isForbidden());
        hide(seller, path, version).andExpect(status().isForbidden());
        for (var protectedAccount : List.of(admin, peerAdmin)) {
            mvc.perform(auth(post("/api/v1/admin/users/" + protectedAccount.id() + "/status"), admin).content("""
                    {"action":"SUSPEND","reason":"Acción protegida","expectedVersion":0}
                    """)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_STATUS_PROTECTED"));
        }
        mvc.perform(auth(post(path + "/moderation"), admin).content("""
                {"action":"SUSPEND","reason":"Acción inválida","expectedVersion":0}
                """)).andExpect(status().isBadRequest());
        var starting = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> {
                starting.await(5, TimeUnit.SECONDS);
                return hide(admin, path, version).andReturn().getResponse().getStatus();
            });
            var two = workers.submit(() -> {
                starting.await(5, TimeUnit.SECONDS);
                return hide(admin, path, version).andReturn().getResponse().getStatus();
            });
            assertEquals(List.of(200, 409), List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        }
        var history = mvc.perform(auth(get(path + "/history"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].actorId").value(admin.id().toString()))
                .andExpect(jsonPath("$.items[0].reason").value("Prueba de auditoría"))
                .andExpect(jsonPath("$.items[0].beforeState").value("UNHIDDEN"))
                .andExpect(jsonPath("$.items[0].afterState").value("HIDDEN"))
                .andExpect(jsonPath("$.items[0].targetVersion").value(version + 1)).andReturn();
        assertEquals(field(history, "items[0].traceId"), sql.sql("""
                SELECT trace_id FROM zonda_market.moderation_audit WHERE listing_id = :id
                """).param("id", listing).query(UUID.class).single().toString());
        confirm(buyer, quote, "mod-oculta").andExpect(status().isConflict());
        assertStock(sku, 5, 0);
        mvc.perform(auth(put(CART + sku), buyer).content("{\"quantity\":1,\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());

        var accountVersion = sql.sql("SELECT version FROM zonda_market.user_account WHERE id = :id")
                .param("id", seller.id()).query(Long.class).single();
        var statusPath = "/api/v1/admin/users/" + seller.id() + "/status";
        mvc.perform(auth(post(statusPath), admin).content("""
                {"action":"SUSPEND","reason":"Prueba de auditoría","expectedVersion":%d}
                """.formatted(accountVersion))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/me"), seller)).andExpect(status().isUnauthorized());
        mvc.perform(auth(post(path + "/moderation"), admin).content("""
                {"action":"RESTORE","reason":"Publicación revisada","expectedVersion":%d}
                """.formatted(version + 1))).andExpect(status().isOk());
        confirm(buyer, quote, "mod-suspendida").andExpect(status().isConflict());
        assertStock(sku, 5, 0);

        mvc.perform(auth(post(statusPath), admin).content("""
                {"action":"REACTIVATE","reason":"Cuenta revisada","expectedVersion":%d}
                """.formatted(accountVersion + 1))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/me"), seller)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/v1/me"), login(seller))).andExpect(status().isOk());
        var newQuote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var newOrder = uuid(confirm(buyer, newQuote, "mod-reactivada").andExpect(status().isOk()).andReturn());
        assertEquals("ACTIVA", reservationState(newOrder));
        assertStock(sku, 4, 1);
        confirm(buyer, newQuote, "mod-reactivada").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newOrder.toString()));
        assertStock(sku, 4, 1);
    }

    private ResultActions hide(Account actor, String path, long version) throws Exception {
        return mvc.perform(auth(post(path + "/moderation"), actor).content("""
                {"action":"HIDE","reason":"  Prueba de auditoría  ","expectedVersion":%d}
                """.formatted(version)));
    }
}
