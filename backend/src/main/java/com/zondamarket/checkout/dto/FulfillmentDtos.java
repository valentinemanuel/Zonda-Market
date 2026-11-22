package com.zondamarket.checkout.dto;

import com.zondamarket.checkout.dto.CheckoutDtos.SubOrder;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FulfillmentDtos {
    private FulfillmentDtos() {}

    public enum Carrier {
        ANDREANI("https://andreani.com/envio/%s"),
        CORREO_ARGENTINO("https://www.correoargentino.com.ar/seguimiento-de-envios");

        private final String trackingTemplate;

        Carrier(String trackingTemplate) {
            this.trackingTemplate = trackingTemplate;
        }

        public String trackingUrl(String code) {
            return trackingTemplate.formatted(code);
        }
    }

    public enum SellerOrderStatus { PREPARANDO, DESPACHADA, ENTREGADA, REEMBOLSADA }

    public record ShipRequest(@NotNull @Pattern(regexp = "ANDREANI|CORREO_ARGENTINO") String carrier,
            @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,63}") String trackingCode,
            @NotNull @PositiveOrZero @Max(Integer.MAX_VALUE) @Digits(integer = 10, fraction = 0) BigDecimal expectedVersion) {}

    public record DeliveryConfirmRequest(
            @NotNull @PositiveOrZero @Max(Integer.MAX_VALUE) @Digits(integer = 10, fraction = 0) BigDecimal expectedVersion) {}

    public record Shipment(Carrier carrier, String trackingCode, String trackingUrl, Instant shippedAt, Instant deliveredAt) {
        public static Shipment of(Carrier carrier, String code, Instant shippedAt, Instant deliveredAt) {
            return new Shipment(carrier, code, carrier.trackingUrl(code), shippedAt, deliveredAt);
        }
    }

    public record ShipmentResponse(UUID id, String status, int version, Shipment shipment) {}

    public record SellerOrderPage(List<SubOrder> content, int page, int size, boolean hasNext) {}
}
