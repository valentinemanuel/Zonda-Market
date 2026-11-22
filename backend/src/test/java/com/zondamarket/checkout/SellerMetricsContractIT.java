package com.zondamarket.checkout;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class SellerMetricsContractIT extends CheckoutContractFixtures {
    private static final String METRICS = "/api/v1/seller/metrics";
    private static final String SETTLEMENTS = "/api/v1/seller/settlements";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired CheckoutContractIT.TestClock clock;

    @Test
    void reconcilesThreeOrdersAtLocalDayBoundariesWithoutExposingOtherSellers() throws Exception {
        clock.set(Instant.parse("2026-11-21T02:59:59Z"));
        var alpha = account(true);
        var beta = account(true);
        var buyer = account(true);
        var alphaSku = product(alpha, "Alfa ventas", "100.25", "5.10", 3);
        var betaSku = product(beta, "Beta ventas", "250.90", "7.00", 3);
        commission(1250);
        var first = paid(buyer, List.of(alphaSku, betaSku));
        var firstAlpha = field(first, "subOrders[0].id");
        var firstBeta = field(first, "subOrders[1].id");

        clock.set(Instant.parse("2026-11-21T03:00:00Z"));
        commission(750);
        var second = paid(account(true), List.of(alphaSku));
        String secondAlpha = field(second, "subOrders[0].id");
        mvc.perform(auth(post("/api/v1/seller/sub-orders/" + secondAlpha + "/ship"), alpha).content("""
                {"carrier":"ANDREANI","trackingCode":"360000000000000","expectedVersion":0}
                """)).andExpect(status().isOk());

        clock.set(Instant.parse("2026-11-22T02:59:59Z"));
        UUID refund = UUID.randomUUID();
        assertEquals(1, refund(refund, UUID.fromString(firstAlpha)));
        assertEquals(0, refund(refund, UUID.fromString(firstAlpha)));
        clock.set(Instant.parse("2026-11-22T03:00:00Z"));
        paid(account(true), List.of(betaSku));
        commission(0);
        alpha = login(alpha);
        beta = login(beta);
        buyer = login(buyer);

        mvc.perform(get(METRICS)).andExpect(status().isUnauthorized());
        mvc.perform(get(SETTLEMENTS)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(METRICS), buyer)).andExpect(status().isForbidden());
        mvc.perform(auth(get(SETTLEMENTS), buyer)).andExpect(status().isForbidden());
        mvc.perform(auth(get(METRICS), alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(30))
                .andExpect(jsonPath("$.to").value("2026-11-22"));
        mvc.perform(auth(get(METRICS), alpha).param("from", "2026-01-01").param("to", "2026-03-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.series.length()").value(90))
                .andExpect(jsonPath("$.net").value("0.00"));
        mvc.perform(auth(get(METRICS), alpha).param("from", "2026-01-01").param("to", "2026-04-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SELLER_DATE_RANGE"));
        mvc.perform(auth(get(METRICS), alpha).param("from", "2026-11-22")).andExpect(status().isBadRequest());
        mvc.perform(auth(get(SETTLEMENTS), alpha).param("from", "2026-11-31").param("to", "2026-12-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(SETTLEMENTS), alpha).param("size", "51")).andExpect(status().isBadRequest());

        mvc.perform(auth(get(METRICS), alpha).param("from", "2026-11-20").param("to", "2026-11-21"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.currency").value("ARS"))
                .andExpect(jsonPath("$.timeZone").value("America/Argentina/Buenos_Aires"))
                .andExpect(jsonPath("$.gross").value("210.70"))
                .andExpect(jsonPath("$.commission").value("20.05"))
                .andExpect(jsonPath("$.refunds").value("21.07"))
                .andExpect(jsonPath("$.commissionReversed").value("2.51"))
                .andExpect(jsonPath("$.net").value("172.09"))
                .andExpect(jsonPath("$.ordersByState.PREPARANDO").value(1))
                .andExpect(jsonPath("$.ordersByState.DESPACHADA").value(1))
                .andExpect(jsonPath("$.series[0].date").value("2026-11-20"))
                .andExpect(jsonPath("$.series[0].net").value("92.82"))
                .andExpect(jsonPath("$.series[1].date").value("2026-11-21"))
                .andExpect(jsonPath("$.series[1].net").value("79.27"));
        mvc.perform(auth(get(METRICS), beta).param("from", "2026-11-20").param("to", "2026-11-22"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gross").value("515.80"))
                .andExpect(jsonPath("$.commission").value("50.18"))
                .andExpect(jsonPath("$.refunds").value("0.00"))
                .andExpect(jsonPath("$.net").value("465.62"))
                .andExpect(jsonPath("$.series[1].gross").value("0.00"));

        mvc.perform(auth(get(SETTLEMENTS), alpha).param("from", "2026-11-21").param("to", "2026-11-21").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content[0].subOrderId").value(firstAlpha))
                .andExpect(jsonPath("$.content[0].recordedAt").value("2026-11-21T02:59:59Z"))
                .andExpect(jsonPath("$.content[0].gross").value("0.00"))
                .andExpect(jsonPath("$.content[0].refunds").value("21.07"))
                .andExpect(jsonPath("$.content[0].commissionReversed").value("2.51"))
                .andExpect(jsonPath("$.content[0].net").value("-18.56"))
                .andExpect(jsonPath("$.content[0].address").doesNotExist());
        mvc.perform(auth(get(SETTLEMENTS), alpha).param("from", "2026-11-21").param("to", "2026-11-21")
                .param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content[0].subOrderId").value(secondAlpha))
                .andExpect(jsonPath("$.content[0].net").value("97.83"));
        mvc.perform(auth(get(SETTLEMENTS), beta).param("from", "2026-11-20").param("to", "2026-11-21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].subOrderId").value(firstBeta));
    }

    private void commission(int bps) {
        sql.sql("UPDATE zonda_market.category SET commission_bps = :bps WHERE id = :id")
                .param("bps", bps).param("id", CATEGORY).update();
    }

    private int refund(UUID id, UUID subOrder) {
        return sql.sql("""
                INSERT INTO zonda_market.checkout_sub_order_refund
                    (refund_id, sub_order_id, merchandise_refunded, shipping_refunded, commission_reversed, confirmed_at, trace_id)
                VALUES (:id, :sub, 20.05, 1.02, 2.51, :now, :trace)
                ON CONFLICT (refund_id) DO NOTHING
                """).param("id", id).param("sub", subOrder)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("trace", UUID.randomUUID()).update();
    }

    private MvcResult paid(Account buyer, List<UUID> skus) throws Exception {
        for (int i = 0; i < skus.size(); i++) add(buyer, skus.get(i), 1, i);
        var quote = uuid(quote(buyer, address(buyer), skus.size()).andExpect(status().isOk()).andReturn());
        var reservation = confirm(buyer, quote, "ventas").andExpect(status().isOk()).andReturn();
        return mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + uuid(reservation) + "/payment-outcome"), buyer)
                .content("""
                        {"reference":"%s","outcome":"APPROVED"}
                        """.formatted(field(reservation, "testPayment.reference")))).andExpect(status().isOk()).andReturn();
    }
}
