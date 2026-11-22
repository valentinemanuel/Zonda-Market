package com.zondamarket.checkout;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class CheckoutContractFixtures {
    static final UUID CATEGORY = UUID.fromString("e2010000-0000-4000-8000-000000000001");
    static final String QUOTE = "/api/v1/checkout/quote";
    static final String CONFIRM = "/api/v1/checkout/confirm";
    static final String ORDERS = "/api/v1/orders/";
    static final String CART = "/api/v1/cart/items/";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;

    ResultActions quote(Account buyer, UUID address, long version) throws Exception {
        return mvc.perform(auth(post(QUOTE), buyer)
                .content("{\"addressId\":\"%s\",\"cartVersion\":%d}".formatted(address, version)));
    }

    ResultActions confirm(Account buyer, UUID quote, String key) throws Exception {
        return mvc.perform(auth(post(CONFIRM), buyer).header("Idempotency-Key", key)
                .content("{\"quoteId\":\"%s\"}".formatted(quote)));
    }

    void add(Account buyer, UUID sku, int quantity, long version) throws Exception {
        mvc.perform(auth(put(CART + sku), buyer).content("{\"quantity\":%d,\"expectedVersion\":%d}".formatted(quantity, version)))
                .andExpect(status().isOk());
    }

    UUID address(Account buyer) throws Exception {
        return uuid(mvc.perform(auth(post("/api/v1/me/addresses"), buyer).content("""
                {"street":"Calle Ficticia 123","city":"La Plata","provinceCode":"AR-B","postalCode":"1900","country":"AR"}
                """)).andExpect(status().isCreated()).andReturn());
    }

    String reservationState(UUID order) {
        return sql.sql("SELECT status FROM zonda_market.stock_reservation WHERE order_id = :id")
                .param("id", order).query(String.class).single();
    }

    int eventCount(UUID order, String type) {
        return sql.sql("SELECT count(*) FROM zonda_market.checkout_outbox WHERE order_id = :id AND event_type = :type")
                .param("id", order).param("type", type).query(Integer.class).single();
    }

    Account account(boolean verified) throws Exception {
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

    Account login(Account account) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"password-ficticia-e302"}
                """.formatted(account.email()))).andExpect(status().isOk()).andReturn();
        return new Account(account.id(), field(result, "accessToken"), account.email());
    }

    UUID product(String name, String price, String shipping, int stock) throws Exception {
        return product(account(true), name, price, shipping, stock);
    }

    UUID product(Account owner, String name, String price, String shipping, int stock) throws Exception {
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

    void assertStock(UUID sku, int available, int reserved) {
        assertEquals(List.of(available, reserved), sql.sql("""
                SELECT available_stock, reserved_stock FROM zonda_market.listing_sku WHERE id = :sku
                """).param("sku", sku).query((row, i) ->
                    List.of(row.getInt("available_stock"), row.getInt("reserved_stock"))).single());
    }

    static UUID uuid(MvcResult result) throws Exception {
        return UUID.fromString(field(result, "id"));
    }

    static String field(MvcResult result, String field) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + field);
    }

    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Account account) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token()).contentType(MediaType.APPLICATION_JSON);
    }

    record Account(UUID id, String token, String email) {}
}
