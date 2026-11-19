package com.zondamarket.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.zondamarket.catalog.domain.Listing;
import com.zondamarket.catalog.domain.ListingSku;
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

public final class ListingDtos {
    private ListingDtos() {}

    public record PublicationRequest(
            @NotNull UUID categoryId, @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 5000) String description, @NotNull Map<String, Object> specs) {}

    public record PublicationPatch(
            @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @Max(Long.MAX_VALUE) BigDecimal expectedVersion, UUID categoryId,
            @Size(max = 160) String title, @Size(max = 5000) String description, Map<String, Object> specs) {}

    public record SkuRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String code,
            @NotNull @Size(max = 16) Map<String, Object> attributes,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal price,
            @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 0) @Max(Integer.MAX_VALUE) BigDecimal initialStock) {}

    public record SkuPatch(
            @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @Max(Long.MAX_VALUE) BigDecimal expectedVersion, @Size(max = 16) Map<String, Object> attributes,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal price) {
        @JsonAnySetter
        public void rejectUnexpectedProperty(String name, Object value) {
            throw new IllegalArgumentException("Campo fuera del contrato de edición de SKU.");
        }
    }

    public record ListingSummary(UUID id, String title, Listing.Status status) {
        public static ListingSummary from(Listing listing) {
            return new ListingSummary(listing.getId(), listing.getTitle(), listing.getStatus());
        }
    }

    public record ListingResponse(UUID id, UUID categoryId, String title, String description,
                                  Map<String, Object> specs, Listing.Status status, long version, List<SkuResponse> skus) {
        public static ListingResponse from(Listing listing, List<ListingSku> skus) {
            return new ListingResponse(listing.getId(), listing.getCategoryId(), listing.getTitle(),
                    listing.getDescription(), listing.getSpecs(), listing.getStatus(), listing.getVersion(),
                    skus.stream().map(SkuResponse::from).toList());
        }
    }

    public record SkuResponse(UUID id, String code, Map<String, String> attributes, String price,
                              int availableStock, long version) {
        public static SkuResponse from(ListingSku sku) {
            return new SkuResponse(sku.getId(), sku.getCode(), sku.getAttributes(), sku.getPrice().toPlainString(),
                    sku.getAvailableStock(), sku.getVersion());
        }
    }
}
