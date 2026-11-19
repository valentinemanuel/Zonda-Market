package com.zondamarket.catalog.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StockDtos {
    private StockDtos() {}

    public record AdjustmentRequest(
            @NotNull @Digits(integer = 10, fraction = 0) @Min(Integer.MIN_VALUE) @Max(Integer.MAX_VALUE) BigDecimal delta,
            @NotBlank @Size(max = 240) String reason,
            @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @Max(Long.MAX_VALUE) BigDecimal expectedVersion) {}

    public record Movement(UUID id, UUID skuId, int delta, String reason, int beforeStock, int afterStock,
                           UUID actorId, UUID traceId, long version, Instant createdAt) {}

    public record MovementPage(List<Movement> items, int page, int size, long totalElements, long totalPages) {}
}
