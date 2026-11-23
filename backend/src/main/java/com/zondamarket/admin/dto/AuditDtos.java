package com.zondamarket.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AuditDtos {
    private AuditDtos() {}

    public record AuditEventResponse(UUID id, UUID actorId, String role, String action, String resourceType,
            UUID resourceId, UUID orderId, UUID disputeId, JsonNode diff, String result, Instant timestamp, UUID traceId) {}

    public enum IncidentKind { WEBHOOK, COMPENSATION, DISPUTE_REFUND }

    public record PaymentIncident(UUID id, IncidentKind kind, String status, UUID orderId, UUID disputeId, UUID traceId,
            int attempts, Instant updatedAt, boolean canReconcile, Instant reconcileAvailableAt) {}

    public record Page<T>(List<T> items, int page, boolean hasNext) {
        public static <T> Page<T> of(List<T> rows, int page, int size) {
            return new Page<>(rows.size() > size ? List.copyOf(rows.subList(0, size)) : rows, page, rows.size() > size);
        }
    }

    public record ReconcileRequest(@NotBlank @Size(max = 500) String reason) {}

    public record RecoveryResponse(UUID id, UUID incidentId, UUID traceId, Instant queuedAt) {}
}
