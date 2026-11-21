package com.zondamarket.checkout.dto;

import com.zondamarket.identity.dto.ProfileDtos.AddressResponse;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CheckoutDtos {
    private CheckoutDtos() {}

    public record QuoteRequest(@NotNull UUID addressId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE) @Digits(integer = 19, fraction = 0) BigDecimal cartVersion) {}

    public record ConfirmRequest(@NotNull UUID quoteId) {}

    public record Line(UUID skuId, String skuCode, String title, Map<String, String> attributes, int quantity,
                       String unitPrice, String subtotal, int commissionBps, String commission) {}

    public record Group(UUID sellerId, String sellerName, List<Line> items, String merchandise,
                        String shipping, String commission, String subtotal) {}

    public record Snapshot(AddressResponse address, List<Group> groups, String total) {}

    public record QuoteResponse(UUID id, Instant expiresAt, Instant serverNow, long cartVersion,
                                AddressResponse address, List<Group> groups, String total, String currency) {}

    public record SubOrder(UUID id, String status, AddressResponse address, Group group) {}

    public record TestPayment(String reference, String result) {}

    public record OrderResponse(UUID id, Instant expiresAt, Instant serverNow, String status,
                                List<SubOrder> subOrders, String total, String currency, TestPayment testPayment) {}

    public record OrderSummary(UUID id, Instant createdAt, Instant expiresAt, String status, String total, String currency) {}

    public record OrderPage(List<OrderSummary> content, int page, int size, boolean hasNext) {}
}
