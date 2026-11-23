package com.zondamarket.checkout;

import com.zondamarket.checkout.service.LocalPaymentPort;
import com.zondamarket.notification.service.NotificationService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
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
        "zonda.checkout.expiry.enabled=false", "zonda.notifications.worker-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class DisputeContractIT extends CheckoutContractFixtures {
    static final String CASES = "/api/v1/disputes/";
    static final String ADMIN = "/api/v1/admin/disputes";
    static final String REASON = "Problema con el pedido";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired NotificationService notifications;

    @Test
    void protectsTheConversationAndNotifiesOnlyTheAffectedParties() throws Exception {
        var buyer = account(true);
        var seller = account(true);
        var otherSeller = account(true);
        var admin = admin();
        var anotherAdmin = admin();
        var first = product(seller, "Alfa reclamo", "10.00", "0", 2);
        var second = product(otherSeller, "Beta reclamo", "20.00", "0", 2);
        var order = purchase(buyer, first, second);
        UUID sub = order.subOrders().getFirst();
        UUID other = order.subOrders().getLast();

        open(buyer, sub, REASON, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISPUTE_INELIGIBLE"));
        pay(buyer, order.id());
        open(seller, sub, REASON, 0).andExpect(status().isNotFound());
        open(admin, sub, REASON, 0).andExpect(status().isNotFound());
        sql.sql("""
                ALTER TABLE zonda_market.checkout_outbox ADD CONSTRAINT fixture_reject_dispute
                    CHECK (event_type <> 'DISPUTE_OPENED') NOT VALID
                """).update();
        try {
            open(buyer, sub, REASON, 0).andExpect(status().isInternalServerError());
            assertEquals(0, sql.sql("SELECT count(*) FROM zonda_market.dispute WHERE sub_order_id = :sub")
                    .param("sub", sub).query(Integer.class).single());
        } finally {
            sql.sql("ALTER TABLE zonda_market.checkout_outbox DROP CONSTRAINT fixture_reject_dispute").update();
        }
        var dispute = uuid(open(buyer, sub, REASON, 0).andExpect(status().isCreated()).andReturn());
        open(buyer, sub, REASON, 0).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(dispute.toString()));
        open(buyer, sub, "Otro motivo", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_EXISTS"));
        ship(seller, sub, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUB_ORDER_DISPUTED"));
        ship(otherSeller, other, 0).andExpect(status().isOk());
        mvc.perform(auth(get(ORDERS + order.id()), buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.subOrders[0].dispute.id").value(dispute.toString()))
                .andExpect(jsonPath("$.subOrders[0].status").value("PREPARANDO"));

        mvc.perform(get(CASES + dispute)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(CASES + dispute), otherSeller)).andExpect(status().isNotFound());
        message(otherSeller, dispute, "Soy ajeno", UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(auth(get(ADMIN), seller)).andExpect(status().isForbidden());
        assign(seller, dispute).andExpect(status().isForbidden());
        mvc.perform(auth(get(CASES + dispute), seller)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.participants.buyerId").value(buyer.id().toString()))
                .andExpect(jsonPath("$.viewer").value("SELLER"))
                .andExpect(jsonPath("$.messages").isEmpty());

        UUID key = UUID.randomUUID();
        var reply = uuid(message(seller, dispute, "<script>Texto literal</script>", key).andExpect(status().isCreated()).andReturn());
        message(seller, dispute, "<script>Texto literal</script>", key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reply.toString()));
        message(seller, dispute, "Distinto", key).andExpect(status().isConflict());
        message(buyer, dispute, "Gracias", UUID.randomUUID()).andExpect(status().isCreated());
        mvc.perform(auth(get(CASES + dispute).param("size", "1").param("page", "1"), buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].body").value("<script>Texto literal</script>"))
                .andExpect(jsonPath("$.hasNext").value(false));

        mvc.perform(auth(get(ADMIN).param("status", "ABIERTA").param("maxAgeDays", "7"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.id == \'" + dispute + "\')]").isNotEmpty());
        var assignment = assign(admin, dispute).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_MEDIACION")).andReturn();
        assign(anotherAdmin, dispute).andExpect(status().isConflict());
        assign(admin, dispute).andExpect(status().isOk());
        assertEquals(assignment.getResponse().getHeader("X-Trace-Id"), sql.sql("""
                SELECT trace_id FROM zonda_market.checkout_outbox WHERE sub_order_id = :id
                    AND event_type = 'DISPUTE_ASSIGNED' AND actor_id = :admin
                """).param("id", sub).param("admin", admin.id()).query(UUID.class).single().toString());
        assertEquals(2, sql.sql("""
                SELECT count(*) FROM zonda_market.checkout_outbox WHERE sub_order_id = :id
                    AND event_type IN ('DISPUTE_OPENED','DISPUTE_ASSIGNED')
                """).param("id", sub).query(Integer.class).single());

        while (notifications.projectNext()) {
            // Drain the local outbox without invoking the mail transport.
        }
        assertEquals(List.of("DISPUTE_ASSIGNED", "DISPUTE_ASSIGNED", "DISPUTE_OPENED"), sql.sql("""
                SELECT type FROM zonda_market.purchase_notification WHERE resource_id = :id AND target_path = :path ORDER BY type
                """).param("id", dispute).param("path", "/reclamos/" + dispute).query(String.class).list());
        assertEquals(0, sql.sql("""
                SELECT count(*) FROM zonda_market.purchase_notification WHERE resource_id = :id AND recipient_id NOT IN (:parties)
                """).param("id", dispute).param("parties", List.of(buyer.id(), seller.id())).query(Integer.class).single());
    }

    @Test
    void serializesOpeningWithShipmentAndBlocksDeliveryAfterOpening() throws Exception {
        var buyer = account(true);
        var seller = account(true);
        var order = purchase(buyer, product(seller, "Carrera", "10.00", "0", 2));
        UUID sub = order.subOrders().getFirst();
        pay(buyer, order.id());

        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var claim = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return open(buyer, sub, REASON, 0);
            });
            var shipment = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return ship(seller, sub, 0);
            });
            var claimResult = claim.get(10, TimeUnit.SECONDS);
            var shipmentResult = shipment.get(10, TimeUnit.SECONDS);
            if (claimResult.andReturn().getResponse().getStatus() == 201) {
                shipmentResult.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUB_ORDER_DISPUTED"));
            } else {
                shipmentResult.andExpect(status().isOk());
                claimResult.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUB_ORDER_VERSION_CONFLICT"));
                open(buyer, sub, REASON, 1).andExpect(status().isCreated());
                deliver(buyer, sub).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUB_ORDER_DISPUTED"));
            }
        }
        var deliveryBuyer = account(true);
        var deliverySeller = account(true);
        var shippedOrder = purchase(deliveryBuyer, product(deliverySeller, "Ya despachado", "10.00", "0", 2));
        UUID shippedSub = shippedOrder.subOrders().getFirst();
        pay(deliveryBuyer, shippedOrder.id());
        ship(deliverySeller, shippedSub, 0).andExpect(status().isOk());
        open(deliveryBuyer, shippedSub, REASON, 1).andExpect(status().isCreated());
        deliver(deliveryBuyer, shippedSub).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUB_ORDER_DISPUTED"));
    }

    @Test
    void acceptsDeliveredCasesWithinSevenDaysAndFiltersTheQueueByAge() throws Exception {
        var buyer = account(true);
        var admin = admin();
        var order = purchase(buyer, product("Reciente", "10.00", "0", 2),
                product("Antiguo", "10.00", "0", 2));
        pay(buyer, order.id());
        var recent = order.subOrders().getFirst();
        var old = order.subOrders().getLast();
        markDelivered(recent, 6);
        markDelivered(old, 8);
        UUID dispute = uuid(open(buyer, recent, REASON, 2).andExpect(status().isCreated()).andReturn());
        open(buyer, old, REASON, 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISPUTE_INELIGIBLE"));
        mvc.perform(auth(get(ADMIN).param("maxAgeDays", "7"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.id == '" + dispute + "')]").isNotEmpty());
        sql.sql("UPDATE zonda_market.dispute SET opened_at = now() - interval '8 days' WHERE id = :id")
                .param("id", dispute).update();
        mvc.perform(auth(get(ADMIN).param("maxAgeDays", "7"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.id == '" + dispute + "')]").isEmpty());
    }

    private void markDelivered(UUID sub, int daysAgo) {
        assertEquals(1, sql.sql("""
                UPDATE zonda_market.checkout_sub_order SET status = 'ENTREGADA', fulfillment_version = 2,
                    carrier = 'ANDREANI', tracking_code = 'E603', shipped_at = now() - interval '10 days',
                    delivered_at = now() - :days * interval '1 day' WHERE id = :id
                """).param("id", sub).param("days", daysAgo).update());
    }

    private Account admin() throws Exception {
        var account = account(true);
        sql.sql("UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE id = :id")
                .param("id", account.id()).update();
        return account;
    }

    private Purchase purchase(Account buyer, UUID... skus) throws Exception {
        for (int i = 0; i < skus.length; i++) add(buyer, skus[i], 1, i);
        var quote = uuid(quote(buyer, address(buyer), skus.length).andExpect(status().isOk()).andReturn());
        var order = confirm(buyer, quote, quote.toString()).andExpect(status().isOk()).andReturn();
        List<String> ids = com.jayway.jsonpath.JsonPath.read(order.getResponse().getContentAsString(), "$.subOrders[*].id");
        return new Purchase(uuid(order), ids.stream().map(UUID::fromString).toList());
    }

    private void pay(Account buyer, UUID order) throws Exception {
        mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + order + "/payment-outcome"), buyer).content("""
                {"reference":"%s","outcome":"APPROVED"}
                """.formatted(LocalPaymentPort.reference(order)))).andExpect(status().isOk());
    }

    private ResultActions open(Account actor, UUID sub, String reason, int version) throws Exception {
        return mvc.perform(auth(post("/api/v1/me/sub-orders/" + sub + "/disputes"), actor).content("""
                {"reason":"%s","description":"Detalle ficticio","expectedVersion":%d}
                """.formatted(reason, version)));
    }

    private ResultActions ship(Account actor, UUID sub, int version) throws Exception {
        return mvc.perform(auth(post("/api/v1/seller/sub-orders/" + sub + "/ship"), actor).content("""
                {"carrier":"ANDREANI","trackingCode":"E603-FICTICIO","expectedVersion":%d}
                """.formatted(version)));
    }

    private ResultActions deliver(Account buyer, UUID sub) throws Exception {
        return mvc.perform(auth(post("/api/v1/me/sub-orders/" + sub + "/confirm-delivery"), buyer)
                .content("{\"expectedVersion\":1}"));
    }

    private ResultActions assign(Account actor, UUID dispute) throws Exception {
        return mvc.perform(auth(post(ADMIN + "/" + dispute + "/assign"), actor));
    }

    private ResultActions message(Account actor, UUID dispute, String body, UUID key) throws Exception {
        return mvc.perform(auth(post(CASES + dispute + "/messages"), actor).header("Idempotency-Key", key)
                .content("{\"body\":\"" + body + "\"}"));
    }

    private record Purchase(UUID id, List<UUID> subOrders) {}
}
