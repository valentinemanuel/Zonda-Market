package com.zondamarket.checkout.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DisputeDtos {
    private DisputeDtos() {}

    public enum Status {
        ABIERTA, EN_MEDIACION, REEMBOLSO_PENDIENTE, CERRADA_SIN_REEMBOLSO, CERRADA_REEMBOLSADA;

        public boolean active() {
            return this == ABIERTA || this == EN_MEDIACION || this == REEMBOLSO_PENDIENTE;
        }
    }

    public enum ParticipantRole { BUYER, SELLER, ADMIN }

    public record OpenRequest(@NotBlank @Size(max = 120) String reason,
            @NotBlank @Size(max = 2000) String description,
            @PositiveOrZero @Max(Integer.MAX_VALUE) @Digits(integer = 10, fraction = 0) BigDecimal expectedVersion) {}

    public record MessageRequest(@NotBlank @Size(max = 2000) String body) {}
    public record Reference(UUID id, Status status) {}
    public record Participants(UUID buyerId, UUID sellerId, UUID mediatorId) {}
    public record Message(UUID id, ParticipantRole authorRole, String body, Instant createdAt) {}
    public record Summary(UUID id, UUID subOrderId, Status status, String reason, Instant openedAt, Instant assignedAt) {}
    public record CasePage(List<Summary> content, int page, int size, boolean hasNext) {}
    public record Detail(UUID id, UUID orderId, UUID subOrderId, Status status, String reason, String description,
                         Instant openedAt, Instant assignedAt, Participants participants, ParticipantRole viewer,
                         List<Message> messages, int page, int size, boolean hasNext) {}
}
