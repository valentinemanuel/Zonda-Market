package com.zondamarket.checkout;

import com.zondamarket.checkout.dto.FulfillmentDtos.ShipRequest;
import com.zondamarket.checkout.service.FulfillmentService;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.notification.service.NotificationMailWorker;
import com.zondamarket.notification.service.NotificationService;
import com.zondamarket.shared.mail.MailSender;
import com.zondamarket.shared.mail.MailSender.DeliveryException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.checkout.expiry.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(CheckoutContractIT.TimeConfiguration.class)
class NotificationContractIT extends CheckoutContractFixtures {
    private static final String INBOX = "/api/v1/me/notifications";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired NotificationService notifications;
    @Autowired NotificationMailWorker mailWorker;
    @Autowired FulfillmentService fulfillment;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CheckoutContractIT.TestClock clock;
    @MockitoBean MailSender mail;

    @Test
    void projectsCommittedMilestonesForEachRecipientAndRetriesMailWithTheSameId() throws Exception {
        clock.set(Instant.now());
        var buyer = account(true);
        var alpha = account(true);
        var beta = account(true);
        var outsider = account(true);
        add(buyer, product(alpha, "Alfa aviso", "100.00", "0.00", 2), 1, 0);
        add(buyer, product(beta, "Beta aviso", "150.00", "0.00", 2), 1, 1);
        var quote = uuid(quote(buyer, address(buyer), 2).andExpect(status().isOk()).andReturn());
        var reservation = confirm(buyer, quote, "notificaciones").andExpect(status().isOk()).andReturn();
        UUID order = uuid(reservation);
        UUID firstSub = UUID.fromString(field(reservation, "subOrders[0].id"));
        UUID secondSub = UUID.fromString(field(reservation, "subOrders[1].id"));
        var pay = """
                {"reference":"%s","outcome":"APPROVED"}
                """.formatted(field(reservation, "testPayment.reference"));

        mvc.perform(get(INBOX)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(INBOX), buyer)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        assertFalse(notifications.projectNext());
        for (int i = 0; i < 2; i++) {
            mvc.perform(auth(post("/api/v1/test-fixtures/orders/" + order + "/payment-outcome"), buyer).content(pay))
                    .andExpect(status().isOk());
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(notifications::projectNext);
            var second = executor.submit(notifications::projectNext);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        when(mail.send(any(), anyString(), anyString(), anyString())).thenReturn("aceptado");
        assertTrue(mailWorker.deliverNext());
        assertTrue(mailWorker.deliverNext());
        assertTrue(mailWorker.deliverNext());
        assertFalse(mailWorker.deliverNext());
        verify(mail).send(any(), eq(buyer.email()), eq("Pago aprobado"), contains("/checkout/reserva/" + order));
        verify(mail).send(any(), eq(alpha.email()), eq("Pago aprobado"), contains("/vendedor/pedidos?suborden=" + firstSub));
        verify(mail).send(any(), eq(beta.email()), eq("Pago aprobado"), contains("/vendedor/pedidos?suborden=" + secondSub));
        String notice = field(mvc.perform(auth(get(INBOX), buyer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.content[0].resourceId").value(order.toString())).andReturn(), "content[0].id");
        mvc.perform(auth(get(INBOX), outsider)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(auth(patch(INBOX + "/" + notice + "/read"), outsider))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(auth(patch(INBOX + "/" + notice + "/read"), buyer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
        }
        mvc.perform(auth(get(INBOX), buyer)).andExpect(jsonPath("$.unreadCount").value(0));

        var seller = new AccountResponse(alpha.id(), alpha.email(), true, List.of(Role.SELLER), "Alfa ficticio");
        var command = new ShipRequest("ANDREANI", "360000000000000", BigDecimal.ZERO);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            fulfillment.ship(seller, firstSub, command, UUID.randomUUID());
            transaction.setRollbackOnly();
        });
        assertEquals(0, eventCount(order, "SUB_ORDER_SHIPPED"));
        assertFalse(notifications.projectNext());
        assertFalse(mailWorker.deliverNext());

        clock.set(clock.instant().plusSeconds(1));
        fulfillment.ship(seller, firstSub, command, UUID.randomUUID());
        fulfillment.ship(seller, firstSub, command, UUID.randomUUID());
        assertTrue(notifications.projectNext());
        sql.sql("""
                UPDATE zonda_market.checkout_outbox SET notification_pending = TRUE
                WHERE sub_order_id = :sub AND event_type = 'SUB_ORDER_SHIPPED'
                """).param("sub", firstSub).update();
        assertTrue(notifications.projectNext());
        assertFalse(notifications.projectNext());
        var trackingLink = "/checkout/reserva/" + order + "#suborden-" + firstSub;
        mvc.perform(auth(get(INBOX), buyer).param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SUB_ORDER_SHIPPED"))
                .andExpect(jsonPath("$.content[0].resourceId").value(firstSub.toString()))
                .andExpect(jsonPath("$.content[0].href").value(trackingLink));
        when(mail.send(any(), eq(buyer.email()), eq("Pedido despachado"), anyString()))
                .thenThrow(new DeliveryException("Fallo de correo ficticio")).thenReturn("recuperado");
        assertTrue(mailWorker.deliverNext());
        assertFalse(mailWorker.deliverNext());
        clock.set(clock.instant().plusSeconds(6));
        assertTrue(mailWorker.deliverNext());
        assertFalse(mailWorker.deliverNext());
        var ids = ArgumentCaptor.forClass(UUID.class);
        var messages = ArgumentCaptor.forClass(String.class);
        verify(mail, times(2)).send(ids.capture(), eq(buyer.email()), eq("Pedido despachado"), messages.capture());
        assertEquals(ids.getAllValues().getFirst(), ids.getAllValues().getLast());
        assertEquals(messages.getAllValues().getFirst(), messages.getAllValues().getLast());
        assertTrue(messages.getValue().contains(trackingLink));
        assertFalse(messages.getValue().contains("Calle Ficticia"));
        assertFalse(messages.getValue().contains("360000000000000"));

        var deliveryPath = "/api/v1/me/sub-orders/" + firstSub + "/confirm-delivery";
        mvc.perform(auth(post(deliveryPath), buyer).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        assertTrue(notifications.projectNext());
        assertTrue(mailWorker.deliverNext());
        verify(mail).send(any(), eq(alpha.email()), eq("Entrega confirmada"), contains(firstSub.toString()));
        assertFalse(mailWorker.deliverNext());
        mvc.perform(auth(get(INBOX), alpha)).andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.content[0].type").value("DELIVERY_CONFIRMED"));
        mvc.perform(auth(get(INBOX), beta)).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(auth(get("/api/v1/seller/orders"), beta).param("subOrderId", firstSub.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }
}
