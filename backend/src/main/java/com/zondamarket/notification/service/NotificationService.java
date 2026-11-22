package com.zondamarket.notification.service;

import com.zondamarket.notification.dto.NotificationDtos.NotificationPage;
import com.zondamarket.notification.dto.NotificationDtos.NotificationResponse;
import com.zondamarket.notification.dto.NotificationDtos.Type;
import com.zondamarket.notification.repository.NotificationStore;
import com.zondamarket.notification.repository.NotificationStore.Event;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final NotificationStore store;
    private final Clock clock;

    NotificationService(NotificationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public boolean projectNext() {
        var event = store.claimEvent().orElse(null);
        if (event == null) return false;
        var now = clock.instant();
        switch (event.eventType()) {
            case "CHECKOUT_PAID" -> {
                notifyBuyer(event, Type.PAYMENT_APPROVED, event.orderId(), "", now);
                notifySellers(event, Type.PAYMENT_APPROVED, now);
            }
            case "SUB_ORDER_SHIPPED" -> notifyBuyer(event, Type.SUB_ORDER_SHIPPED, event.subOrderId(),
                    "#suborden-" + event.subOrderId(), now);
            case "DELIVERY_CONFIRMED" -> notifySellers(event, Type.DELIVERY_CONFIRMED, now);
            default -> throw new IllegalStateException("Hito de compra desconocido.");
        }
        store.projected(event.id(), now);
        return true;
    }

    private void notifyBuyer(Event event, Type type, UUID resource, String fragment, Instant now) {
        store.buyer(event.orderId()).ifPresent(recipient -> store.enqueue(event, recipient, type, resource,
                "/checkout/reserva/" + event.orderId() + fragment, now));
    }

    private void notifySellers(Event event, Type type, Instant now) {
        for (var seller : store.sellers(event)) {
            store.enqueue(event, seller, type, seller.subOrderId(), "/vendedor/pedidos?suborden=" + seller.subOrderId(), now);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationPage list(UUID recipient, int page, int size) {
        var notifications = store.page(recipient, page, size);
        boolean hasNext = notifications.size() > size;
        return new NotificationPage(hasNext ? notifications.subList(0, size) : notifications,
                page, size, hasNext, store.unreadCount(recipient));
    }

    @Transactional
    public NotificationResponse markRead(UUID recipient, UUID id) {
        return store.markRead(recipient, id, clock.instant()).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "No encontramos esa notificación."));
    }
}
