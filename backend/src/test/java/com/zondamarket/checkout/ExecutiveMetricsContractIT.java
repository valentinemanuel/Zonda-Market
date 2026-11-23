package com.zondamarket.checkout;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(CheckoutContractIT.TimeConfiguration.class)
class ExecutiveMetricsContractIT extends CheckoutContractFixtures {
    private static final String DASHBOARD = "/api/v1/admin/dashboard";
    private static final String EXPORT = "/api/v1/admin/reports/sales.csv";
    private static final String SALE_DAY = "2026-11-20";
    private static final String REFUND_DAY = "2026-11-21";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired CheckoutContractIT.TestClock clock;

    @Test
    void reconcilesTwoSellersOneOrderAndADayWithOnlyARefund() throws Exception {
        clock.set(Instant.parse("2026-11-21T02:59:59Z"));
        var buyer = account(true);
        var admin = account(true);
        sql.sql("UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE id = :id").param("id", admin.id()).update();
        var alpha = account(true);
        var beta = account(true);
        var betaCategory = UUID.randomUUID();
        sql.sql("""
                INSERT INTO zonda_market.category (id, name, slug, created_at, updated_at, commission_bps)
                VALUES (:id, :name, 'categoria-e606', now(), now(), 2000)
                """).param("id", betaCategory).param("name", "Categoría \"B\", prueba").update();
        sql.sql("UPDATE zonda_market.category SET commission_bps = 1000 WHERE id = :id").param("id", CATEGORY).update();
        var alphaSku = product(alpha, "=1+1", "100.00", "10.00", 2);
        uuidForSeller(beta, "Beta informes");
        var betaSku = listingProduct(beta, betaCategory, "Beta informes", "150.00", 2, "{}");
        add(buyer, alphaSku, 1, 0);
        add(buyer, betaSku, 1, 1);
        var quoteId = uuid(quote(buyer, address(buyer), 2).andExpect(status().isOk()).andReturn());
        var order = confirm(buyer, quoteId, "informes").andExpect(status().isOk()).andReturn();
        var payment = post("/api/v1/test-fixtures/orders/" + uuid(order) + "/payment-outcome")
                .content("""
                        {"reference":"%s","outcome":"APPROVED"}
                        """.formatted(field(order, "testPayment.reference")));
        mvc.perform(auth(payment, buyer)).andExpect(status().isOk());
        mvc.perform(payment).andExpect(status().isOk());

        var alphaSub = field(order, "subOrders[0].group.sellerName").equals("=1+1")
                ? field(order, "subOrders[0].id") : field(order, "subOrders[1].id");
        var betaSub = field(order, "subOrders[0].id").equals(alphaSub)
                ? field(order, "subOrders[1].id") : field(order, "subOrders[0].id");
        open(buyer, betaSub);
        clock.set(Instant.parse("2026-11-21T03:00:00Z"));
        var dispute = open(buyer, alphaSub);
        mvc.perform(auth(post("/api/v1/admin/disputes/" + dispute + "/assign"), admin)).andExpect(status().isOk());
        sql.sql("UPDATE zonda_market.dispute SET status = 'CERRADA_REEMBOLSADA' WHERE id = :id").param("id", dispute).update();

        UUID refund = UUID.randomUUID();
        assertEquals(1, insertRefund(refund, alphaSub));
        assertEquals(0, insertRefund(refund, alphaSub));
        sql.sql("""
                UPDATE zonda_market.listing SET category_id = :category
                WHERE id = (SELECT listing_id FROM zonda_market.listing_sku WHERE id = :sku)
                """).param("category", betaCategory).param("sku", alphaSku).update();

        mvc.perform(get(DASHBOARD)).andExpect(status().isUnauthorized());
        mvc.perform(get(EXPORT)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(DASHBOARD), buyer)).andExpect(status().isForbidden());
        mvc.perform(auth(get(EXPORT), buyer)).andExpect(status().isForbidden());
        mvc.perform(auth(get(DASHBOARD).param("from", SALE_DAY), admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ADMIN_DATE_RANGE"));
        mvc.perform(auth(get(EXPORT).param("from", "2026-01-01").param("to", "2026-04-01"), admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ADMIN_DATE_RANGE"));
        mvc.perform(auth(get(DASHBOARD), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(30)).andExpect(jsonPath("$.to").value(REFUND_DAY));
        mvc.perform(auth(get(DASHBOARD).param("from", "2026-01-01").param("to", "2026-03-31"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.series.length()").value(90))
                .andExpect(jsonPath("$.gmv").value("0.00")).andExpect(jsonPath("$.openCases").value(1));

        var result = report(DASHBOARD, admin).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.gmv").value("260.00"))
                .andExpect(jsonPath("$.refunds").value("25.00"))
                .andExpect(jsonPath("$.platformCommission").value("37.50"))
                .andExpect(jsonPath("$.paidOrders").value(1))
                .andExpect(jsonPath("$.disputes").value(2))
                .andExpect(jsonPath("$.openCases").value(1))
                .andExpect(jsonPath("$.series[0].gmv").value("260.00"))
                .andExpect(jsonPath("$.series[0].paidOrders").value(1))
                .andExpect(jsonPath("$.series[1].gmv").value("0.00"))
                .andExpect(jsonPath("$.series[1].refunds").value("25.00"))
                .andExpect(jsonPath("$.series[1].platformCommission").value("-2.50"))
                .andExpect(jsonPath("$.series[1].paidOrders").value(0))
                .andExpect(jsonPath("$.sellers[1].name").value("=1+1"))
                .andExpect(jsonPath("$.sellers[1].gmv").value("110.00"))
                .andExpect(jsonPath("$.sellers[1].platformCommission").value("7.50"))
                .andExpect(jsonPath("$.categories[0].originalCommission").value("30.00"))
                .andExpect(jsonPath("$.categories[1].id").value(CATEGORY.toString()))
                .andExpect(jsonPath("$.categories[1].merchandise").value("100.00"))
                .andReturn();
        var csv = report(EXPORT, admin).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ventas-2026-11-20-2026-11-21.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var rows = csv.lines().toList();
        assertEquals(8, rows.size());
        String scope = "total," + SALE_DAY + "," + REFUND_DAY + ",ARS,America/Argentina/Buenos_Aires,,,,";
        assertEquals(scope + String.join(",", field(result, "gmv"), field(result, "refunds"),
                field(result, "platformCommission"), "1", "2", "1", "", ""), rows.get(1));
        assertTrue(rows.get(3).endsWith(",0.00,25.00,-2.50,0,1,,,"));
        assertTrue(rows.get(5).endsWith(",'=1+1,110.00,25.00,7.50,,,,,"));
        assertTrue(rows.get(6).endsWith(",\"Categoría \"\"B\"\", prueba\",,,,,,,150.00,30.00"));
    }

    private ResultActions report(String endpoint, Account actor) throws Exception {
        MockHttpServletRequestBuilder request = get(endpoint).param("from", SALE_DAY).param("to", REFUND_DAY);
        if (EXPORT.equals(endpoint)) request.accept("text/csv", "application/problem+json");
        return mvc.perform(auth(request, actor));
    }

    private UUID open(Account buyer, String subOrder) throws Exception {
        return uuid(mvc.perform(auth(post("/api/v1/me/sub-orders/" + subOrder + "/disputes"), buyer).content("""
                {"reason":"Reporte de prueba","description":"Descripción ficticia del cliente"}
                """)).andExpect(status().isCreated()).andReturn());
    }

    private int insertRefund(UUID refund, String subOrder) {
        return sql.sql("""
                INSERT INTO zonda_market.checkout_sub_order_refund
                    (refund_id, sub_order_id, merchandise_refunded, shipping_refunded, commission_reversed, confirmed_at, trace_id)
                VALUES (:id, :sub, 25.00, 0.00, 2.50, :date, :trace)
                ON CONFLICT (refund_id) DO NOTHING
                """).param("id", refund).param("sub", UUID.fromString(subOrder))
                .param("date", clock.instant().atOffset(ZoneOffset.UTC)).param("trace", UUID.randomUUID()).update();
    }
}
