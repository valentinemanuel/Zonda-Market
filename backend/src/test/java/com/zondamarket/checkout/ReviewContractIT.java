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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.nullValue;
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
class ReviewContractIT extends CheckoutContractFixtures {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final String COMMENT = "<strong>Comentario ficticio</strong>";
    private static final String REVIEW = "/api/v1/me/sub-orders/";

    @Test
    void publishesOneReviewPerDeliveredSubOrderAndKeepsVisibilityAndReputationInSync() throws Exception {
        var buyer = account(true);
        var anotherBuyer = account(true);
        var alpha = account(true);
        var beta = account(true);
        var alphaSku = product(alpha, "Alfa opinión", "100.00", "0.00", 3);
        var betaSku = product(beta, "Beta opinión", "150.00", "0.00", 3);
        String alphaSlug = sellerSlug(alpha);
        String betaSlug = sellerSlug(beta);
        String profile = "/api/v1/sellers/" + alphaSlug;
        String product = "/api/v1/listings/" + sql.sql("""
                SELECT listing.slug FROM zonda_market.listing listing
                JOIN zonda_market.listing_sku sku ON sku.listing_id = listing.id WHERE sku.id = :sku
                """).param("sku", alphaSku).query(String.class).single();
        String reviews = profile + "/reviews";

        mvc.perform(get(reviews)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reputation.reviewCount").value(0))
                .andExpect(jsonPath("$.reputation.averageRating").value(nullValue()));
        mvc.perform(get("/api/v1/sellers/inexistente/reviews")).andExpect(status().isNotFound());

        var order = paid(buyer, List.of(alphaSku, betaSku));
        String firstSub = field(order, "subOrders[0].id");
        String secondSub = field(order, "subOrders[1].id");
        var commandPath = REVIEW + firstSub + "/review";
        var body = new ObjectMapper().writeValueAsString(java.util.Map.of("rating", 5, "comment", "  " + COMMENT + "  "));
        mvc.perform(post(commandPath)).andExpect(status().isUnauthorized());
        mvc.perform(auth(post(commandPath), anotherBuyer).content(body)).andExpect(status().isNotFound());
        mvc.perform(auth(post(commandPath), alpha).content(body)).andExpect(status().isNotFound());
        mvc.perform(auth(post(commandPath), buyer).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_DELIVERY_REQUIRED"));
        ship(alpha, firstSub);
        mvc.perform(auth(post(commandPath), buyer).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_DELIVERY_REQUIRED"));
        deliver(buyer, firstSub);

        for (String payload : List.of("{\"rating\":0}", "{\"rating\":6}", "{\"rating\":4.5}", "{}",
                "{\"rating\":5,\"comment\":\"" + "x".repeat(1001) + "\"}", "{\"rating\":5,\"comment\":\"\\u0000\"}")) {
            mvc.perform(auth(post(commandPath), buyer).content(payload)).andExpect(status().isBadRequest());
        }
        var barrier = new CyclicBarrier(2);
        MvcResult created;
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = threads.submit(() -> submit(barrier, buyer, commandPath, body));
            var b = threads.submit(() -> submit(barrier, buyer, commandPath, body));
            var responses = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), responses.stream().map(result -> result.getResponse().getStatus()).sorted().toList());
            created = responses.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
        }
        var firstReview = uuid(created);
        assertEquals(List.of(buyer.id(), UUID.fromString(created.getResponse().getHeader("X-Trace-Id"))), sql.sql("""
                SELECT author_id, trace_id FROM zonda_market.seller_review WHERE id = :id
                """).param("id", firstReview).query((row, ignored) ->
                    List.of(row.getObject("author_id", UUID.class), row.getObject("trace_id", UUID.class))).single());
        mvc.perform(auth(post(commandPath), buyer).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_EXISTS"));
        mvc.perform(auth(post(REVIEW + secondSub + "/review"), buyer).content(body)).andExpect(status().isConflict());
        mvc.perform(auth(get(ORDERS + uuid(order)), buyer))
                .andExpect(jsonPath("$.subOrders[0].reviewed").value(true))
                .andExpect(jsonPath("$.subOrders[1].reviewed").value(false));
        mvc.perform(auth(get("/api/v1/seller/orders"), alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewed").value(nullValue()));

        var secondOrder = paid(anotherBuyer, List.of(alphaSku));
        var secondDelivery = field(secondOrder, "subOrders[0].id");
        ship(alpha, secondDelivery);
        deliver(anotherBuyer, secondDelivery);
        var secondReview = uuid(mvc.perform(auth(post(REVIEW + secondDelivery + "/review"), anotherBuyer)
                .content("{\"rating\":2}")).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.comment").value(nullValue()))
                .andReturn());
        mvc.perform(get(reviews).param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(secondReview.toString()))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.reputation.reviewCount").value(2))
                .andExpect(jsonPath("$.reputation.averageRating").value(3.5));
        mvc.perform(get(reviews).param("size", "1").param("page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].comment").value(COMMENT))
                .andExpect(jsonPath("$.content[0].authorAlias").value(org.hamcrest.Matchers.startsWith("Comprador ")))
                .andExpect(jsonPath("$.content[0].authorId").doesNotExist())
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get(profile)).andExpect(status().isOk()).andExpect(jsonPath("$.averageRating").value(3.5));
        mvc.perform(get(product)).andExpect(status().isOk()).andExpect(jsonPath("$.seller.averageRating").value(3.5));
        mvc.perform(get("/api/v1/sellers/" + betaSlug + "/reviews")).andExpect(jsonPath("$.content").isEmpty());

        sql.sql("UPDATE zonda_market.seller_review SET hidden = TRUE WHERE id = :id").param("id", secondReview).update();
        mvc.perform(get(reviews)).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.reputation.averageRating").value(5.0));
        mvc.perform(get(profile)).andExpect(jsonPath("$.reviewCount").value(1));
        mvc.perform(get(product)).andExpect(jsonPath("$.seller.reviewCount").value(1));
        mvc.perform(auth(get(ORDERS + uuid(secondOrder)), anotherBuyer)).andExpect(jsonPath("$.subOrders[0].reviewed").value(true));
        mvc.perform(auth(post(REVIEW + secondDelivery + "/review"), anotherBuyer).content(body)).andExpect(status().isConflict());
    }

    private MvcResult submit(CyclicBarrier barrier, Account buyer, String path, String body) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return mvc.perform(auth(post(path), buyer).content(body)).andReturn();
    }

    private MvcResult paid(Account buyer, List<UUID> skus) throws Exception {
        for (int i = 0; i < skus.size(); i++) add(buyer, skus.get(i), 1, i);
        var quote = uuid(quote(buyer, address(buyer), skus.size()).andExpect(status().isOk()).andReturn());
        var reservation = confirm(buyer, quote, "resena").andExpect(status().isOk()).andReturn();
        return mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + uuid(reservation) + "/payment-outcome"), buyer)
                .content("""
                        {"reference":"%s","outcome":"APPROVED"}
                        """.formatted(field(reservation, "testPayment.reference")))).andExpect(status().isOk()).andReturn();
    }

    private void ship(Account seller, String sub) throws Exception {
        mvc.perform(auth(post("/api/v1/seller/sub-orders/" + sub + "/ship"), seller)
                .content("""
                        {"carrier":"ANDREANI","trackingCode":"360000000000000","expectedVersion":0}
                        """)).andExpect(status().isOk());
    }

    private void deliver(Account buyer, String sub) throws Exception {
        mvc.perform(auth(post(REVIEW + sub + "/confirm-delivery"), buyer).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
    }

    private String sellerSlug(Account seller) {
        return sql.sql("SELECT slug FROM zonda_market.seller_profile WHERE account_id = :id")
                .param("id", seller.id()).query(String.class).single();
    }
}
