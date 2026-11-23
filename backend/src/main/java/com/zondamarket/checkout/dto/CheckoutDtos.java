package com.zondamarket.checkout.dto;

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
            @NotNull @Min(0) @Max(Long.MAX_VALUE) @Digits(integer = 19, fraction = 0) BigDecimal cartVersion,
            UUID directSkuId, @Min(1) @Max(99) @Digits(integer = 2, fraction = 0) BigDecimal quantity) {}

    public record ConfirmRequest(@NotNull UUID quoteId) {}

    public record Line(UUID skuId, String skuCode, String title, Map<String, String> attributes, int quantity,
                       String unitPrice, String subtotal, int commissionBps, String commission) {}

    public record Group(UUID sellerId, String sellerName, List<Line> items, String merchandise,
                        String shipping, String commission, String subtotal) {}

    public record DeliveryAddress(UUID id, String street, String city, String provinceCode, String postalCode,
                                  String country, String recipient, String phone, String deliveryDetails) {}

    public record Snapshot(DeliveryAddress address, List<Group> groups, String total, UUID directSkuId) {}

    public record QuoteResponse(UUID id, Instant expiresAt, Instant serverNow, long cartVersion,
                                DeliveryAddress address, List<Group> groups, String total, String currency,
                                Map<UUID, String> thumbnails) {}

    public record DirectPreview(List<Group> groups, Map<UUID, String> thumbnails) {}

    public record SubOrder(UUID id, String status, DeliveryAddress address, Group group,
                           int version, FulfillmentDtos.Shipment shipment, Boolean reviewed, DisputeDtos.Reference dispute) {}

    public record TestPayment(String reference, String result) {}
    public record ReceiptPayment(PaymentDtos.Method method, String lastFour, boolean simulated) {}

    public record OrderResponse(UUID id, Instant expiresAt, Instant serverNow, String status,
                                List<SubOrder> subOrders, String total, String currency, TestPayment testPayment,
                                List<CompensationDtos.Refund> refunds, String refundedAmount, UUID directSkuId,
                                Map<UUID, String> thumbnails, ReceiptPayment payment) {}

    public enum HistoryFilter { PAGADAS, PREPARANDO, ENTREGADAS }

    public record OrderPreview(String title, String thumbnailUrl) {}

    public record OrderSummary(UUID id, Instant createdAt, Instant expiresAt, String status, String total, String currency,
                               List<OrderPreview> previews, long lineCount, long units, String fulfillment) {}

    public record OrderPage(List<OrderSummary> content, int page, int size, boolean hasNext) {}
}
