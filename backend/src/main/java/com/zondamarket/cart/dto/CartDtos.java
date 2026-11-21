package com.zondamarket.cart.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CartDtos {
    private CartDtos() {}

    public enum UnavailableReason { LISTING_UNAVAILABLE, SKU_UNAVAILABLE, INSUFFICIENT_STOCK, SELF_PURCHASE }

    public record CartItemRequest(
            @NotNull @Min(1) @Max(99) @Digits(integer = 2, fraction = 0) BigDecimal quantity,
            @NotNull @Min(0) @Max(Long.MAX_VALUE) @Digits(integer = 19, fraction = 0) BigDecimal expectedVersion) {}

    public record CartResponse(long version, List<SellerGroup> groups, List<Item> unavailable,
                               String total, String currency, long itemCount, UUID activeOrderId) {}

    public record SellerGroup(UUID sellerId, String sellerSlug, String sellerName, List<Item> items, String subtotal) {}

    public record Item(UUID skuId, String listingSlug, String title, Map<String, String> attributes,
                       int quantity, int availableStock, String unitPrice, String referencePrice,
                       String subtotal, UnavailableReason reason, boolean reserved) {}
}
