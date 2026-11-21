package com.zondamarket.checkout;

import com.jayway.jsonpath.JsonPath;
import com.zondamarket.checkout.service.ReservationExpiry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Import(CheckoutContractIT.TimeConfiguration.class)
class CheckoutContractIT {
    private static final UUID CATEGORY = UUID.fromString("e2010000-0000-4000-8000-000000000001");
    private static final String QUOTE = "/api/v1/checkout/quote";
    private static final String CONFIRM = "/api/v1/checkout/confirm";
    private static final String ORDERS = "/api/v1/orders/";
    private static final String CART = "/api/v1/cart/items/";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;
    @Autowired TestClock clock;
    @Autowired ReservationExpiry expiry;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void freezesTwoSellerOrdersPreservesCartAndReplaysTheOriginalKey() throws Exception {
        var buyer = account(true);
        var outsider = account(true);
        var address = address(buyer);
        sql.sql("UPDATE zonda_market.category SET commission_bps = 1250 WHERE id = :id").param("id", CATEGORY).update();
        var alpha = product("Alfa", "10.05", "1.10", 5);
        var beta = product("Beta", "20.04", "5.00", 3);
        add(buyer, alpha, 2, 0);
        add(buyer, beta, 1, 1);

        var quoteResponse = quote(buyer, address, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.cartVersion").value(2)).andExpect(jsonPath("$.total").value("46.24"))
                .andExpect(jsonPath("$.groups[0].commission").value("2.51"))
                .andExpect(jsonPath("$.groups[1].commission").value("2.51"))
                .andExpect(jsonPath("$.groups[0].shipping").value("1.10")).andReturn();
        var quote = uuid(quoteResponse);
        var alternate = uuid(quote(buyer, address, 2).andExpect(status().isOk()).andReturn());
        assertStock(alpha, 5, 0);
        assertStock(beta, 3, 0);

        var result = confirm(buyer, quote, "original").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("RESERVADA"))
                .andExpect(jsonPath("$.subOrders.length()").value(2))
                .andExpect(jsonPath("$.subOrders[0].status").value("ESPERA_PAGO"))
                .andExpect(jsonPath("$.subOrders[0].group.subtotal").value("21.20"))
                .andExpect(jsonPath("$.subOrders[1].group.subtotal").value("25.04"))
                .andExpect(jsonPath("$.subOrders[0].group.items[0].commissionBps").value(1250))
                .andExpect(jsonPath("$.subOrders[0].address.street").value("Calle Ficticia 123"))
                .andExpect(jsonPath("$.total").value("46.24")).andReturn();
        assertEquals(900, Duration.between(Instant.parse(field(result, "serverNow")),
                Instant.parse(field(result, "expiresAt"))).toSeconds());
        UUID order = uuid(result);
        assertStock(alpha, 3, 2);
        assertStock(beta, 2, 1);
        assertEquals(2, sql.sql("SELECT count(*) FROM zonda_market.stock_reservation WHERE order_id = :id AND status = 'ACTIVA'")
                .param("id", order).query(Integer.class).single());
        assertEquals(1, sql.sql("SELECT count(*) FROM zonda_market.checkout_outbox WHERE order_id = :id")
                .param("id", order).query(Integer.class).single());

        confirm(buyer, quote, "original").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(order.toString()));
        confirm(buyer, alternate, "original").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        confirm(buyer, alternate, "different").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_ACTIVE"));
        mvc.perform(auth(get(ORDERS + order), outsider)).andExpect(status().isNotFound());
        mvc.perform(auth(get(ORDERS + "active"), outsider)).andExpect(status().isNoContent());
        mvc.perform(auth(get(ORDERS + "active"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.toString()));

        mvc.perform(auth(get("/api/v1/cart"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(3)).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.groups[0].items[0].reserved").value(true));
        mvc.perform(auth(delete(CART + alpha), buyer).param("expectedVersion", "2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ITEM_RESERVED"));
        mvc.perform(auth(put(CART + beta), buyer).content("{\"quantity\":2,\"expectedVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CART_ITEM_RESERVED"));
        add(buyer, product("Otra tienda", "10.00", "0.00", 1), 1, 2);

        sql.sql("UPDATE zonda_market.account_address SET street = 'Dirección nueva', deleted = TRUE WHERE id = :id").param("id", address).update();
        sql.sql("UPDATE zonda_market.listing_sku SET price = 500 WHERE id = :id").param("id", alpha).update();
        sql.sql("UPDATE zonda_market.category SET commission_bps = 0 WHERE id = :id").param("id", CATEGORY).update();
        mvc.perform(auth(get(ORDERS + order), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.subOrders[0].address.street").value("Calle Ficticia 123"))
                .andExpect(jsonPath("$.subOrders[0].group.items[0].unitPrice").value("10.05"))
                .andExpect(jsonPath("$.subOrders[0].group.commission").value("2.51"))
                .andExpect(jsonPath("$.total").value("46.24"));
    }

    @Test
    void rejectsForeignAndStaleInputAndRollsBackIfTheOutboxCannotBeWritten() throws Exception {
        var buyer = account(true);
        var other = account(true);
        var unverified = account(false);
        var address = address(buyer);
        var foreign = address(other);
        var sku = product("Stock", "8.50", "0.00", 4);
        add(buyer, sku, 1, 0);
        mvc.perform(post(QUOTE)).andExpect(status().isUnauthorized());
        mvc.perform(post(CONFIRM)).andExpect(status().isUnauthorized());
        quote(unverified, address, 0).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        quote(buyer, foreign, 1).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
        quote(buyer, address, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        var old = uuid(quote(buyer, address, 1).andExpect(status().isOk()).andReturn());
        confirm(other, old, "foreign").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        sql.sql("UPDATE zonda_market.listing_sku SET price = 9.00 WHERE id = :id").param("id", sku).update();
        confirm(buyer, old, "stale").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));
        sql.sql("UPDATE zonda_market.checkout_quote SET created_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", old).update();
        confirm(buyer, old, "expired").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));

        var fresh = uuid(quote(buyer, address, 1).andExpect(status().isOk()).andReturn());
        sql.sql("ALTER TABLE zonda_market.checkout_outbox ADD CONSTRAINT fixture_reject_event CHECK (event_type <> 'CHECKOUT_RESERVED') NOT VALID").update();
        try {
            confirm(buyer, fresh, "retry").andExpect(status().isInternalServerError());
            assertStock(sku, 4, 0);
            mvc.perform(auth(get(ORDERS + "active"), buyer)).andExpect(status().isNoContent());
        } finally {
            sql.sql("ALTER TABLE zonda_market.checkout_outbox DROP CONSTRAINT fixture_reject_event").update();
        }
        confirm(buyer, fresh, "retry").andExpect(status().isOk());
        assertStock(sku, 3, 1);
    }

    @Test
    void serializesBuyersCompetingForTheLastUnit() throws Exception {
        var first = account(true);
        var second = account(true);
        var sku = product("Última unidad", "100.01", "0.00", 1);
        add(first, sku, 1, 0);
        add(second, sku, 1, 0);
        var firstQuote = uuid(quote(first, address(first), 1).andExpect(status().isOk()).andReturn());
        var secondQuote = uuid(quote(second, address(second), 1).andExpect(status().isOk()).andReturn());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> race(barrier, first, firstQuote));
            var b = executor.submit(() -> race(barrier, second, secondQuote));
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), results.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
            int loser = results.getFirst().getResponse().getStatus() == 409 ? 0 : 1;
            assertEquals("STOCK_CHANGED", field(results.get(loser), "code"));
            mvc.perform(auth(get(ORDERS + "active"), List.of(first, second).get(loser))).andExpect(status().isNoContent());
            mvc.perform(auth(get("/api/v1/cart"), List.of(first, second).get(loser)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1));
        }
        assertStock(sku, 0, 1);
    }

    private MvcResult race(CyclicBarrier barrier, Account buyer, UUID quote) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return confirm(buyer, quote, "same-key").andReturn();
    }

    @Test
    void expiresExactlyOnceAtTheDeadlineAndKeepsTheCart() throws Exception {
        var buyer = account(true);
        var outsider = account(true);
        var address = address(buyer);
        var alpha = product("Expira Alfa", "10.05", "0.00", 6);
        var beta = product("Expira Beta", "20.04", "0.00", 4);
        add(buyer, alpha, 2, 0);
        add(buyer, beta, 3, 1);
        var quote = uuid(quote(buyer, address, 2).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "initial").andExpect(status().isOk()).andReturn());
        Instant expiresAt = clock.instant().plusSeconds(900);

        clock.set(expiresAt.minusSeconds(1));
        final var validBuyer = login(buyer);
        expiry.sweep();
        mvc.perform(auth(get(ORDERS + order), validBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVADA"));
        assertStock(alpha, 4, 2);
        assertStock(beta, 1, 3);

        clock.set(expiresAt);
        mvc.perform(auth(get(ORDERS + order), login(outsider))).andExpect(status().isNotFound());
        assertStock(alpha, 4, 2);

        var start = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var scheduled = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); expiry.sweep(); return null; });
            var reopened = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(auth(get(ORDERS + order), validBuyer)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("EXPIRADA")).andReturn();
            });
            var rejected = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return confirm(validBuyer, quote, "new-at-deadline").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("CHECKOUT_EXPIRED")).andReturn();
            });
            scheduled.get(15, TimeUnit.SECONDS);
            reopened.get(15, TimeUnit.SECONDS);
            rejected.get(15, TimeUnit.SECONDS);
        }
        expiry.sweep();
        assertStock(alpha, 6, 0);
        assertStock(beta, 4, 0);
        assertEquals(1, sql.sql("""
                SELECT count(*) FROM zonda_market.checkout_outbox WHERE order_id = :id AND event_type = 'CHECKOUT_EXPIRED'
                """).param("id", order).query(Integer.class).single());
        assertEquals(2, sql.sql("""
                SELECT count(*) FROM zonda_market.stock_reservation WHERE order_id = :id AND status = 'LIBERADA'
                """).param("id", order).query(Integer.class).single());
        confirm(validBuyer, quote, "initial").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.toString()))
                .andExpect(jsonPath("$.subOrders.length()").value(2))
                .andExpect(jsonPath("$.subOrders[0].status").value("CANCELADA"))
                .andExpect(jsonPath("$.subOrders[1].status").value("CANCELADA"));
        mvc.perform(auth(get(ORDERS + "active"), validBuyer)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/orders?size=1"), validBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(order.toString()))
                .andExpect(jsonPath("$.content[0].status").value("EXPIRADA"));
        mvc.perform(auth(get("/api/v1/orders"), login(outsider))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(auth(get("/api/v1/cart"), validBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.itemCount").value(5))
                .andExpect(jsonPath("$.groups[0].items[0].reserved").value(false));

        var replacement = uuid(quote(validBuyer, address, 2).andExpect(status().isOk()).andReturn());
        confirm(validBuyer, replacement, "replacement").andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/orders?size=1"), validBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(auth(get("/api/v1/orders?page=1&size=1"), validBuyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(order.toString()));
    }

    @Test
    void aDifferentBuyerCanQuoteTheSkuOfAnExpiredReservationWithTheWorkerDisabled() throws Exception {
        var first = account(true);
        var second = account(true);
        var sku = product("Oportunista", "8.50", "0.00", 1);
        add(first, sku, 1, 0);
        add(second, sku, 1, 0);
        var secondAddress = address(second);
        var quote = uuid(quote(first, address(first), 1).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(first, quote, "first").andExpect(status().isOk()).andReturn());
        clock.set(clock.instant().plusSeconds(900));

        var validBuyer = login(second);
        var quoteAfterExpiry = uuid(quote(validBuyer, secondAddress, 1).andExpect(status().isOk()).andReturn());
        assertStock(sku, 1, 0);
        confirm(validBuyer, quoteAfterExpiry, "second").andExpect(status().isOk());
        assertStock(sku, 0, 1);
        mvc.perform(auth(get(ORDERS + order), login(first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRADA"));
    }

    private ResultActions quote(Account buyer, UUID address, long version) throws Exception {
        return mvc.perform(auth(post(QUOTE), buyer)
                .content("{\"addressId\":\"%s\",\"cartVersion\":%d}".formatted(address, version)));
    }

    private ResultActions confirm(Account buyer, UUID quote, String key) throws Exception {
        return mvc.perform(auth(post(CONFIRM), buyer).header("Idempotency-Key", key)
                .content("{\"quoteId\":\"%s\"}".formatted(quote)));
    }

    private void add(Account buyer, UUID sku, int quantity, long version) throws Exception {
        mvc.perform(auth(put(CART + sku), buyer).content("{\"quantity\":%d,\"expectedVersion\":%d}".formatted(quantity, version)))
                .andExpect(status().isOk());
    }

    private UUID address(Account buyer) throws Exception {
        return uuid(mvc.perform(auth(post("/api/v1/me/addresses"), buyer).content("""
                {"street":"Calle Ficticia 123","city":"La Plata","provinceCode":"AR-B","postalCode":"1900","country":"AR"}
                """)).andExpect(status().isCreated()).andReturn());
    }

    private Account account(boolean verified) throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        var session = mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"password-ficticia-e302"}
                """.formatted(email))).andExpect(status().isCreated()).andReturn();
        var token = field(session, "accessToken");
        var account = new Account(uuid(mvc.perform(auth(get("/api/v1/me"), new Account(null, token, email)))
                .andExpect(status().isOk()).andReturn()), token, email);
        if (verified) {
            sql.sql("UPDATE zonda_market.user_account SET email_verified = TRUE WHERE id = :id").param("id", account.id()).update();
        }
        return account;
    }

    private Account login(Account account) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"password-ficticia-e302"}
                """.formatted(account.email()))).andExpect(status().isOk()).andReturn();
        return new Account(account.id(), field(result, "accessToken"), account.email());
    }

    private UUID product(String name, String price, String shipping, int stock) throws Exception {
        var owner = account(true);
        var seller = uuidForSeller(owner, name);
        sql.sql("UPDATE zonda_market.seller_profile SET shipping_cost = :shipping WHERE id = :id")
                .param("shipping", new BigDecimal(shipping)).param("id", seller).update();
        String listings = "/api/v1/seller/listings";
        UUID listing = uuid(mvc.perform(auth(post(listings), owner).content("""
                {"categoryId":"%s","title":"Producto %s","description":"Producto ficticio","specs":{}}
                """.formatted(CATEGORY, name))).andExpect(status().isCreated()).andReturn());
        UUID sku = uuid(mvc.perform(auth(post(listings + "/" + listing + "/skus"), owner).content("""
                {"code":"SKU-%s","attributes":{"color":"Azul","talle":"M"},"price":%s,"initialStock":%d}
                """.formatted(UUID.randomUUID(), price, stock))).andExpect(status().isCreated()).andReturn());
        sql.sql("""
                INSERT INTO zonda_market.listing_image (id, listing_id, provider, public_id, url, thumbnail_url, position, created_at, updated_at)
                VALUES (:id, :listing, 'local', :id, '/fixture', '/fixture', 0, now(), now())
                """).param("id", UUID.randomUUID()).param("listing", listing).update();
        mvc.perform(auth(post(listings + "/" + listing + "/publish"), owner)).andExpect(status().isOk());
        return sku;
    }

    private UUID uuidForSeller(Account owner, String name) throws Exception {
        mvc.perform(auth(post("/api/v1/me/seller-profile"), owner).content("""
                {"type":"PERSONAL","displayName":"%s","slug":"tienda-%s"}
                """.formatted(name, UUID.randomUUID()))).andExpect(status().isCreated());
        return sql.sql("SELECT id FROM zonda_market.seller_profile WHERE account_id = :id")
                .param("id", owner.id()).query(UUID.class).single();
    }

    private void assertStock(UUID sku, int available, int reserved) {
        assertEquals(List.of(available, reserved), sql.sql("""
                SELECT available_stock, reserved_stock FROM zonda_market.listing_sku WHERE id = :sku
                """).param("sku", sku).query((row, i) ->
                    List.of(row.getInt("available_stock"), row.getInt("reserved_stock"))).single());
    }

    private static UUID uuid(MvcResult result) throws Exception {
        return UUID.fromString(field(result, "id"));
    }

    private static String field(MvcResult result, String field) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + field);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Account account) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token()).contentType(MediaType.APPLICATION_JSON);
    }

    private record Account(UUID id, String token, String email) {}

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary TestClock checkoutTestClock() { return new TestClock(); }
    }

    static final class TestClock extends Clock {
        private volatile Instant time = Instant.now();

        void set(Instant time) { this.time = time; }
        @Override public Instant instant() { return time; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time, zone); }
    }
}
