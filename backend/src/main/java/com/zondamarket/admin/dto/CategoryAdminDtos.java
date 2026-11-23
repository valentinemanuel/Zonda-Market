package com.zondamarket.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CategoryAdminDtos {
    private CategoryAdminDtos() {}

    public record CategoryAdminRequest(
            UUID parentId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 120) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
            @NotNull @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 0) BigDecimal commissionBps,
            @NotNull @Size(max = 16) Map<String, Object> attributeSchema,
            @PositiveOrZero @Digits(integer = 19, fraction = 0) @Max(Long.MAX_VALUE) BigDecimal expectedVersion) {}

    public record ArchiveRequest(
            @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @Max(Long.MAX_VALUE) BigDecimal expectedVersion) {}

    public record CategoryRow(UUID id, UUID parentId, String name, String slug, int commissionBps,
                              Map<String, List<String>> attributeSchema, long version, boolean archived,
                              long activeListings, boolean hasChildren) {}
}
