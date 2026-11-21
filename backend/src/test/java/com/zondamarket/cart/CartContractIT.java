package com.zondamarket.cart;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class CartContractIT {
    private static final String CART = "/api/v1/cart";
    private static final String LISTINGS = "/api/v1/seller/listings";
    private static final String PASSWORD = "password-ficticia-e301";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;

    @Test
    void persistsByAccountAndQuotesTwoSellersWithLivePricesAndAvailability() throws Exception {
        var buyer = account(null);
        var first = offer(account("Alfa ficticio"));
        var seller = account("Beta ficticio");
        var second = offer(seller);

        fetch(buyer).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.total").value("0.00"));
        save(buyer, first.skuId(), 2, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        save(buyer, second.skuId(), 1, 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        var otherSession = new Account(buyer.id(), buyer.email(), session("login", buyer.email()));
        fetch(otherSession).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].sellerName").value("Alfa ficticio"))
                .andExpect(jsonPath("$.groups[0].subtotal").value("199.98"))
                .andExpect(jsonPath("$.groups[1].sellerName").value("Beta ficticio"))
                .andExpect(jsonPath("$.groups[1].subtotal").value("99.99"))
                .andExpect(jsonPath("$.groups[0].items[0].attributes.color").value("Azul"))
                .andExpect(jsonPath("$.currency").value("ARS"))
                .andExpect(jsonPath("$.total").value("299.97"))
                .andExpect(jsonPath("$.itemCount").value(3));
        assertStock(first.skuId(), 7, 0);
        assertStock(second.skuId(), 7, 0);

        sql.sql("UPDATE zonda_market.listing_sku SET price = 105.55, available_stock = 1 WHERE id = :id")
                .param("id", first.skuId()).update();
        fetch(buyer).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.unavailable[0].reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.unavailable[0].availableStock").value(1))
                .andExpect(jsonPath("$.unavailable[0].referencePrice").value("99.99"))
                .andExpect(jsonPath("$.unavailable[0].unitPrice").value("105.55"))
                .andExpect(jsonPath("$.total").value("99.99"));
        save(buyer, first.skuId(), 1, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].items[0].referencePrice").value("105.55"))
                .andExpect(jsonPath("$.total").value("205.54"));

        mvc.perform(auth(post(LISTINGS + "/" + second.listingId() + "/archive"), seller)).andExpect(status().isOk());
        sql.sql("UPDATE zonda_market.listing SET title = 'Cambio aún privado' WHERE id = :id")
                .param("id", second.listingId()).update();
        fetch(buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable[0].reason").value("LISTING_UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailable[0].title").value(second.title()))
                .andExpect(jsonPath("$.unavailable[0].listingSlug").isEmpty())
                .andExpect(jsonPath("$.total").value("105.55"));
        save(buyer, second.skuId(), 1, 3).andExpect(status().isNotFound());
        remove(buyer, second.skuId(), 3).andExpect(status().isOk()).andExpect(jsonPath("$.unavailable").isEmpty())
                .andExpect(jsonPath("$.version").value(4));
        remove(buyer, first.skuId(), 4).andExpect(status().isOk()).andExpect(jsonPath("$.groups").isEmpty())
                .andExpect(jsonPath("$.version").value(5)).andExpect(jsonPath("$.total").value("0.00"));
        save(buyer, first.skuId(), 1, 5).andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1));
        assertStock(first.skuId(), 1, 0);
    }

    @Test
    void enforcesAccountOwnershipAndValidQuantity() throws Exception {
        var seller = account("Vendedor ficticio");
        var offer = offer(seller);
        var buyer = account(null);
        var unrelated = account(null);
        String path = CART + "/items/" + offer.skuId();
        mvc.perform(get(CART)).andExpect(status().isUnauthorized());
        mvc.perform(put(path)).andExpect(status().isUnauthorized());
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());

        save(seller, offer.skuId(), 1, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_PURCHASE"));
        for (String quantity : List.of("0", "100", "1.5", "null")) {
            mvc.perform(auth(put(path), buyer).content("{\"quantity\":" + quantity + ",\"expectedVersion\":0}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(auth(put(path), buyer).content("{\"quantity\":1,\"expectedVersion\":9223372036854775808}"))
                .andExpect(status().isBadRequest());
        save(buyer, offer.skuId(), 8, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        save(buyer, offer.skuId(), 2, 0).andExpect(status().isOk());
        fetch(unrelated).andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(0));
        remove(unrelated, offer.skuId(), 0).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
        fetch(buyer).andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(2));
    }

    @Test
    void rejectsStaleConcurrentEditsAndAllowsRetryAgainstTheNewVersion() throws Exception {
        var seller = account("Vendedor ficticio");
        var buyer = account(null);
        var item = offer(seller);
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentSave(start, buyer, item.skuId(), 2));
            var second = pool.submit(() -> concurrentSave(start, buyer, item.skuId(), 3));
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), results.stream().map(result -> result.getResponse().getStatus()).sorted().toList());
            var winner = results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow();
            var conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertEquals("CART_VERSION_CONFLICT", field(conflict, "code"));
            int winningQuantity = field(winner, "itemCount");
            fetch(buyer).andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.itemCount").value(winningQuantity));
        }
        remove(buyer, item.skuId(), 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"));
        save(buyer, item.skuId(), 4, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.itemCount").value(4));
        assertStock(item.skuId(), 7, 0);
    }

    private MvcResult concurrentSave(CyclicBarrier start, Account buyer, UUID skuId, int quantity) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return save(buyer, skuId, quantity, 0).andReturn();
    }

    private ResultActions fetch(Account buyer) throws Exception {
        return mvc.perform(auth(get(CART), buyer));
    }

    private ResultActions save(Account buyer, UUID skuId, int quantity, long version) throws Exception {
        return mvc.perform(auth(put(CART + "/items/" + skuId), buyer)
                .content("{\"quantity\":%d,\"expectedVersion\":%d}".formatted(quantity, version)));
    }

    private ResultActions remove(Account buyer, UUID skuId, long version) throws Exception {
        return mvc.perform(auth(delete(CART + "/items/" + skuId), buyer).param("expectedVersion", Long.toString(version)));
    }

    private void assertStock(UUID id, int available, int reserved) {
        assertEquals(List.of(available, reserved), sql.sql("""
                SELECT available_stock, reserved_stock FROM zonda_market.listing_sku WHERE id = :id
                """).param("id", id).query((row, number) ->
                List.of(row.getInt("available_stock"), row.getInt("reserved_stock"))).single());
    }

    private Offer offer(Account seller) throws Exception {
        String title = "Producto " + UUID.randomUUID();
        var listing = mvc.perform(auth(post(LISTINGS), seller).content("""
                {"categoryId":"e2010000-0000-4000-8000-000000000001","title":"%s","description":"Prueba","specs":{}}
                """.formatted(title))).andExpect(status().isCreated()).andReturn();
        UUID listingId = UUID.fromString(field(listing, "id"));
        var sku = mvc.perform(auth(post(LISTINGS + "/" + listingId + "/skus"), seller).content("""
                {"code":"SKU-%s","attributes":{"color":"Azul","talle":"M"},"price":99.99,"initialStock":7}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        // The cart only requires a visible catalog offer. Media upload is exercised in Playwright.
        sql.sql("""
                INSERT INTO zonda_market.listing_image
                    (id, listing_id, provider, public_id, url, thumbnail_url, position, created_at, updated_at)
                VALUES (:id, :listing, 'local', :id, '/fixture', '/fixture', 0, now(), now())
                """).param("id", UUID.randomUUID()).param("listing", listingId).update();
        mvc.perform(auth(post(LISTINGS + "/" + listingId + "/publish"), seller)).andExpect(status().isOk());
        return new Offer(listingId, UUID.fromString(field(sku, "id")), title);
    }

    private Account account(String sellerName) throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String token = session("register", email);
        var self = mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var account = new Account(UUID.fromString(field(self, "id")), email, token);
        if (sellerName != null) {
            sql.sql("UPDATE zonda_market.user_account SET email_verified = true WHERE id = :id")
                    .param("id", account.id()).update();
            mvc.perform(auth(post("/api/v1/me/seller-profile"), account).content("""
                    {"type":"PERSONAL","displayName":"%s","slug":"carrito-%s"}
                    """.formatted(sellerName, UUID.randomUUID()))).andExpect(status().isCreated());
        }
        return account;
    }

    private String session(String action, String email) throws Exception {
        return field(mvc.perform(post("/api/v1/auth/" + action).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().is(action.equals("register") ? 201 : 200)).andReturn(), "accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Account account) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token()).contentType(MediaType.APPLICATION_JSON);
    }

    private static <T> T field(MvcResult result, String name) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + name);
    }

    private record Account(UUID id, String email, String token) {}
    private record Offer(UUID listingId, UUID skuId, String title) {}
}
