package com.zondamarket.demo;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.demo.seed.enabled=true",
        "zonda.demo.seed.password=participantes-ficticios",
        "zonda.demo.seed.admin-password=administracion-ficticia"
})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("demo")
class DemoSeedContractIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired JdbcClient sql;
    @Autowired DemoSeedRunner seed;
    @Autowired MockMvc mvc;

    @Test
    void seedsReplaceableCredentialsAndCoherentHistoryOnce() throws Exception {
        var initial = fingerprint();
        assertEquals("1|7|5|80|240|80|3|4|2|3|12000.00|1", initial);
        assertEquals(5, sql.sql("SELECT count(DISTINCT seller_id) FROM zonda_market.listing")
                .query(Integer.class).single());
        assertEquals(0, sql.sql("""
                SELECT count(*) FROM zonda_market.checkout_order o
                WHERE o.total <> (SELECT sum(s.merchandise + s.shipping)
                                  FROM zonda_market.checkout_sub_order s WHERE s.order_id = o.id)
                """).query(Integer.class).single());
        assertEquals(0, sql.sql("""
                SELECT count(*) FROM zonda_market.listing_image
                WHERE provider <> 'external' OR url NOT LIKE 'https://images.pexels.com/%'
                """).query(Integer.class).single());

        var timestamp = sql.sql("SELECT seeded_at::text FROM zonda_market.demo_seed_run").query(String.class).single();
        seed.run(null);
        assertEquals(initial, fingerprint());
        assertEquals(timestamp, sql.sql("SELECT seeded_at::text FROM zonda_market.demo_seed_run").query(String.class).single());

        String buyer = login("comprador", "participantes-ficticios");
        String admin = login("admin", "administracion-ficticia");
        mvc.perform(get("/api/v1/listings/demo-auriculares-nuevo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1)).andExpect(jsonPath("$.skus.length()").value(3));
        mvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3));
        mvc.perform(get("/api/v1/admin/dashboard").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paidOrders").value(2))
                .andExpect(jsonPath("$.gmv").value("140000.00")).andExpect(jsonPath("$.refunds").value("12000.00"))
                .andExpect(jsonPath("$.platformCommission").value("4280.00")).andExpect(jsonPath("$.openCases").value(1));
        mvc.perform(get("/api/v1/admin/dashboard").header(HttpHeaders.AUTHORIZATION, buyer))
                .andExpect(status().isForbidden());
        UUID order = sql.sql("SELECT id FROM zonda_market.checkout_order WHERE idempotency_key = 'demo-dividida'")
                .query(UUID.class).single();
        mvc.perform(post("/api/v1/test-fixtures/orders/" + order + "/payment-outcome")
                .header(HttpHeaders.AUTHORIZATION, buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"APPROVED\",\"reference\":\"local-" + order + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesProductionEvenWhenDemoIsAlsoSet() {
        for (String production : new String[] {"prod", "production"}) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles("demo", production);
            var runner = new DemoSeedRunner(null, null, Clock.systemUTC(), environment, null, "", "");
            assertThrows(IllegalStateException.class, () -> runner.run(null));
        }
    }

    private String login(String role, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000").header("X-Zonda-CSRF", "1")
                .content("{\"email\":\"" + role + "@demo.zonda.test\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + JsonPath.<String>read(response.getResponse().getContentAsString(), "$.accessToken");
    }

    private String fingerprint() {
        return sql.sql("""
                SELECT concat_ws('|',
                    (SELECT count(*) FROM zonda_market.demo_seed_run),
                    (SELECT count(*) FROM zonda_market.user_account),
                    (SELECT count(*) FROM zonda_market.seller_profile),
                    (SELECT count(*) FROM zonda_market.listing),
                    (SELECT count(*) FROM zonda_market.listing_sku),
                    (SELECT count(*) FROM zonda_market.listing_image),
                    (SELECT count(*) FROM zonda_market.checkout_order),
                    (SELECT count(*) FROM zonda_market.checkout_sub_order),
                    (SELECT count(*) FROM zonda_market.checkout_payment_outcome),
                    (SELECT count(*) FROM zonda_market.checkout_sub_order_settlement),
                    (SELECT sum(merchandise_refunded + shipping_refunded) FROM zonda_market.checkout_sub_order_refund),
                    (SELECT count(*) FROM zonda_market.seller_review))
                """).query(String.class).single();
    }
}
