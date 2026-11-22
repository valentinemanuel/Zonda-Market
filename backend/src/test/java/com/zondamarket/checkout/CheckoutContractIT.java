package com.zondamarket.checkout;

import com.zondamarket.checkout.service.LocalPaymentPort;
import com.zondamarket.checkout.service.PaymentConfirmationService;
import com.zondamarket.checkout.service.ReservationExpiry;
import com.zondamarket.shared.errors.ApiProblemException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
@ActiveProfiles("test")
@Import(CheckoutContractIT.TimeConfiguration.class)
class CheckoutContractIT extends CheckoutContractFixtures {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired TestClock clock;
    @Autowired ReservationExpiry expiry;
    @Autowired PaymentConfirmationService payment;

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

    @Test
    void approvesOnceAndKeepsOtherCartLinesEvenAfterAFailedWrite() throws Exception {
        var buyer = account(true);
        var other = account(true);
        var sku = product("Vendido", "8.50", "0.00", 4);
        var unrelated = product("Otro producto", "3.00", "0.00", 4);
        add(buyer, sku, 2, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var created = confirm(buyer, quote, "approval").andExpect(status().isOk()).andReturn();
        UUID order = uuid(created);
        String reference = LocalPaymentPort.reference(order);
        assertEquals(reference, field(created, "testPayment.reference"));
        add(buyer, unrelated, 1, 1);
        outcome(other, order, reference, "APPROVED").andExpect(status().isNotFound());
        outcome(buyer, order, "local-otra-orden", "APPROVED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFERENCE_MISMATCH"));
        var wrongAmount = new LocalPaymentPort.VerifiedOutcome(reference, LocalPaymentPort.Outcome.APPROVED, BigDecimal.ONE, "ARS");
        assertEquals("PAYMENT_AMOUNT_MISMATCH", assertThrows(ApiProblemException.class,
                () -> payment.applyVerifiedOutcome(buyer.id(), order, wrongAmount, UUID.randomUUID()))
                .getBody().getProperties().get("code"));

        sql.sql("ALTER TABLE zonda_market.checkout_payment_outcome ADD CONSTRAINT fixture_fail_payment CHECK (false) NOT VALID").update();
        try {
            outcome(buyer, order, reference, "APPROVED").andExpect(status().isInternalServerError());
            assertStock(sku, 2, 2);
            assertEquals("ACTIVA", reservationState(order));
            assertEquals(0, eventCount(order, "CHECKOUT_PAID"));
        } finally {
            sql.sql("ALTER TABLE zonda_market.checkout_payment_outcome DROP CONSTRAINT fixture_fail_payment").update();
        }

        clock.set(clock.instant().plusSeconds(899));
        var fresh = login(buyer);
        outcome(fresh, order, reference, "APPROVED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"))
                .andExpect(jsonPath("$.testPayment.result").value("APPROVED"))
                .andExpect(jsonPath("$.subOrders[0].status").value("PREPARANDO"));
        clock.set(clock.instant().plusSeconds(1));
        expiry.sweep();
        outcome(fresh, order, reference, "APPROVED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"));
        outcome(fresh, order, reference, "REJECTED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_OUTCOME_CONFLICT"));
        mvc.perform(auth(get("/api/v1/cart"), fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.groups[0].items[0].skuId").value(unrelated.toString()));
        assertEquals("CONSUMIDA", reservationState(order));
        assertStock(sku, 2, 0);
        assertEquals(1, eventCount(order, "CHECKOUT_PAID"));
        assertEquals(0, eventCount(order, "CHECKOUT_EXPIRED"));
    }

    @Test
    void rejectsAndRestoresStockWithoutChangingTheCart() throws Exception {
        var buyer = account(true);
        var sku = product("Rechazado", "8.50", "0.00", 1);
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "rejection").andExpect(status().isOk()).andReturn());
        String reference = LocalPaymentPort.reference(order);
        outcome(buyer, order, reference, "REJECTED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALLIDA"))
                .andExpect(jsonPath("$.subOrders[0].status").value("CANCELADA"))
                .andExpect(jsonPath("$.testPayment.result").value("REJECTED"));
        outcome(buyer, order, reference, "REJECTED").andExpect(status().isOk());
        assertStock(sku, 1, 0);
        assertEquals("LIBERADA", reservationState(order));
        assertEquals(1, eventCount(order, "CHECKOUT_PAYMENT_REJECTED"));
        mvc.perform(auth(get("/api/v1/cart"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.itemCount").value(1));
    }

    @Test
    void reconcilesApprovalRacingExpirationAtTheDeadline() throws Exception {
        var buyer = account(true);
        var sku = product("Carrera pago", "8.50", "0.00", 1);
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        UUID order = uuid(confirm(buyer, quote, "race-payment").andExpect(status().isOk()).andReturn());
        String reference = LocalPaymentPort.reference(order);
        clock.set(clock.instant().plusSeconds(900));
        var fresh = login(buyer);
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var scheduled = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); expiry.sweep(); return null; });
            var approved = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return outcome(fresh, order, reference, "APPROVED").andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("EXPIRADA"))
                        .andExpect(jsonPath("$.testPayment.result").value("LATE_APPROVAL")).andReturn();
            });
            scheduled.get(15, TimeUnit.SECONDS);
            approved.get(15, TimeUnit.SECONDS);
        }
        outcome(fresh, order, reference, "APPROVED").andExpect(status().isOk());
        assertStock(sku, 1, 0);
        assertEquals("LIBERADA", reservationState(order));
        assertEquals(1, eventCount(order, "CHECKOUT_EXPIRED"));
        assertEquals(1, eventCount(order, "LOCAL_PAYMENT_LATE"));
        assertEquals(0, eventCount(order, "CHECKOUT_PAID"));
        mvc.perform(auth(get("/api/v1/cart"), fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1));
    }

    private ResultActions outcome(Account buyer, UUID order, String reference, String result) throws Exception {
        return mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + order + "/payment-outcome"), buyer)
                .content("""
                        {"reference":"%s","outcome":"%s"}
                        """.formatted(reference, result)));
    }

    @Test
    void serializesPaymentAttemptsAndRecoversAnUncertainSubmission() throws Exception {
        var buyer = account(true);
        var outsider = account(true);
        var sku = product("Intento", "8.50", "0.00", 2);
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "reservation").andExpect(status().isOk()).andReturn());
        var path = ORDERS + order;
        mvc.perform(auth(get(path + "/payment-options"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.methods.length()").value(2))
                .andExpect(jsonPath("$.methods[0]").value("CARD"))
                .andExpect(jsonPath("$.methods[1]").value("CHECKOUT_PRO"));
        mvc.perform(auth(get(path + "/payment-options"), outsider)).andExpect(status().isNotFound());
        beginAttempt(outsider, order, "CARD", "outsider").andExpect(status().isNotFound());

        var start = new CyclicBarrier(2);
        MvcResult card;
        MvcResult pro;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return beginAttempt(buyer, order, "CARD", "card").andReturn();
            });
            var second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return beginAttempt(buyer, order, "CHECKOUT_PRO", "pro").andReturn();
            });
            card = first.get(15, TimeUnit.SECONDS);
            pro = second.get(15, TimeUnit.SECONDS);
        }
        boolean cardWon = card.getResponse().getStatus() == 200;
        var winner = cardWon ? card : pro;
        var loser = cardWon ? pro : card;
        assertEquals(200, winner.getResponse().getStatus());
        assertEquals(409, loser.getResponse().getStatus());
        assertEquals("PAYMENT_ATTEMPT_ACTIVE", field(loser, "code"));
        var attempt = uuid(winner);
        var winnerMethod = cardWon ? "CARD" : "CHECKOUT_PRO";
        var winnerKey = cardWon ? "card" : "pro";
        var otherMethod = cardWon ? "CHECKOUT_PRO" : "CARD";
        assertEquals("RESULTADO_DESCONOCIDO", field(winner, "status"));
        mvc.perform(auth(get(path + "/payment-options"), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.methods").isEmpty())
                .andExpect(jsonPath("$.latestAttempt.id").value(attempt.toString()));
        mvc.perform(auth(get(path + "/payments/" + attempt), buyer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.nextAction").value("CONSULTAR"))
                .andExpect(jsonPath("$.providerKey").doesNotExist());
        mvc.perform(auth(get(path + "/payments/" + attempt), outsider)).andExpect(status().isNotFound());
        beginAttempt(buyer, order, winnerMethod, winnerKey).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attempt.toString()));
        beginAttempt(buyer, order, otherMethod, winnerKey).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        outcome(buyer, order, LocalPaymentPort.reference(order), "APPROVED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ATTEMPT_ACTIVE"));
        reconcileAttempt(outsider, order, attempt, "APROBADO").andExpect(status().isNotFound());
        reconcileAttempt(buyer, order, attempt, "PENDIENTE").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE"));
        reconcileAttempt(buyer, order, attempt, "RECHAZADO").andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAction").value("ELEGIR_MEDIO"));
        assertStock(sku, 1, 1);
        var retry = uuid(beginAttempt(buyer, order, otherMethod, "retry").andExpect(status().isOk()).andReturn());
        reconcileAttempt(buyer, order, attempt, "APROBADO").andExpect(status().isConflict());
        reconcileAttempt(buyer, order, retry, "APROBADO").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROBADO"));
        reconcileAttempt(buyer, order, retry, "APROBADO").andExpect(status().isOk());
        beginAttempt(buyer, order, winnerMethod, "after-paid").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_INELIGIBLE"));
        mvc.perform(auth(get(path), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGADA"));
        assertStock(sku, 1, 0);
        assertEquals(1, eventCount(order, "CHECKOUT_PAID"));
        assertEquals(2, sql.sql("SELECT count(*) FROM zonda_market.payment_attempt WHERE order_id = :order")
                .param("order", order).query(Integer.class).single());
    }

    @Test
    void refusesNewAttemptsAtTheDeadlineAndRecordsLateApproval() throws Exception {
        var buyer = account(true);
        var sku = product("Intento tardío", "8.50", "0.00", 1);
        add(buyer, sku, 1, 0);
        var quote = uuid(quote(buyer, address(buyer), 1).andExpect(status().isOk()).andReturn());
        var order = uuid(confirm(buyer, quote, "reserved").andExpect(status().isOk()).andReturn());
        var attempt = uuid(beginAttempt(buyer, order, "CARD", "first").andExpect(status().isOk()).andReturn());
        clock.set(clock.instant().plusSeconds(900));
        var fresh = login(buyer);
        beginAttempt(fresh, order, "CHECKOUT_PRO", "too-late").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_INELIGIBLE"));
        reconcileAttempt(fresh, order, attempt, "APROBADO").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROBADO"));
        mvc.perform(auth(get(ORDERS + order), fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRADA"));
        assertStock(sku, 1, 0);
        assertEquals(1, eventCount(order, "CHECKOUT_EXPIRED"));
        assertEquals(1, eventCount(order, "LOCAL_PAYMENT_LATE"));
        assertEquals(0, eventCount(order, "CHECKOUT_PAID"));
    }

    private ResultActions beginAttempt(Account buyer, UUID order, String method, String key) throws Exception {
        return mvc.perform(auth(post(ORDERS + order + "/payments"), buyer).header("Idempotency-Key", key)
                .content("{\"method\":\"%s\"}".formatted(method)));
    }

    private ResultActions reconcileAttempt(Account buyer, UUID order, UUID attempt, String result) throws Exception {
        return mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + order + "/payments/" + attempt + "/outcome"), buyer)
                .content("{\"outcome\":\"%s\"}".formatted(result)));
    }

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
