package com.zondamarket.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}

    public enum Type {
        PAYMENT_APPROVED("Pago aprobado", "Se acreditó el pago de tu pedido."),
        SUB_ORDER_SHIPPED("Pedido despachado", "El vendedor registró el despacho de tu pedido."),
        DELIVERY_CONFIRMED("Entrega confirmada", "El comprador confirmó que recibió el pedido."),
        DISPUTE_OPENED("Reclamo abierto", "Se abrió un reclamo sobre una suborden."),
        DISPUTE_ASSIGNED("Reclamo en mediación", "Administración tomó el reclamo para mediación.");

        private final String subject;
        private final String message;

        Type(String subject, String message) {
            this.subject = subject;
            this.message = message;
        }

        public String subject() { return subject; }
        public String message() { return message; }
    }

    public record NotificationResponse(UUID id, Type type, Instant createdAt, boolean read, UUID resourceId, String href) {}

    public record NotificationPage(List<NotificationResponse> content, int page, int size, boolean hasNext, long unreadCount) {}
}
