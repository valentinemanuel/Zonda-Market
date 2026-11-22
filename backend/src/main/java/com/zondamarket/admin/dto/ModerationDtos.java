package com.zondamarket.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ModerationDtos {
    private ModerationDtos() {}

    public enum ListingAction { HIDE, RESTORE }
    public enum AccountAction { SUSPEND, REACTIVATE }
    public enum State { UNHIDDEN, HIDDEN, ACTIVE, SUSPENDED }

    public record Command<A>(@NotNull A action, @NotBlank @Size(max = 500) String reason,
                             @NotNull @PositiveOrZero Long expectedVersion) {}

    public record ListingRow(UUID id, String title, String slug, String sellerEmail, String status,
                             boolean hidden, boolean visible, boolean sellerSuspended, long version) {}
    public record AccountRow(UUID id, String email, String displayName, String role, boolean suspended, long version) {}
    public record Audit(UUID actorId, String actorEmail, String action, String reason, String beforeState,
                        String afterState, long targetVersion, UUID traceId, OffsetDateTime createdAt) {}
    public record OrderCase(UUID orderId, UUID subOrderId, String orderStatus, String subOrderStatus,
                            String sellerName, OffsetDateTime createdAt, boolean requiresFollowUp) {}

    public record Page<T>(List<T> items, int page, boolean hasNext) {
        public static <T> Page<T> from(List<T> items, int page, int size) {
            boolean hasNext = items.size() > size;
            return new Page<>(hasNext ? items.subList(0, size) : items, page, hasNext);
        }
    }
}
