package com.zondamarket.checkout.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class DisputeResolutionDtos {
    private DisputeResolutionDtos() {}

    public enum Outcome { REEMBOLSO, SIN_REEMBOLSO }
    public enum Status { CERRADA, PENDIENTE, CONFIRMADA, RECHAZADA, EN_REVISION }

    public record ResolveRequest(@NotNull Outcome outcome,
            @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 2000) String reason,
            @NotNull @PositiveOrZero @Max(Integer.MAX_VALUE) @Digits(integer = 10, fraction = 0) BigDecimal expectedVersion) {}

    public record Resolution(UUID id, Outcome outcome, Status status, String amount, String reason,
                             String providerRefundId, boolean simulated, Instant updatedAt) {}

    public record Balance(String subOrderAvailable, String paymentAvailable, String available, String currency) {}
}
