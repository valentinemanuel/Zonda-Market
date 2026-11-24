package com.zondamarket.checkout.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.checkout.dto.FulfillmentDtos.*;
import com.zondamarket.checkout.service.FulfillmentService;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Despacho y entrega")
@SecurityRequirement(name = "bearerAuth")
class FulfillmentController {
    private final FulfillmentService fulfillment;

    FulfillmentController(FulfillmentService fulfillment) {
        this.fulfillment = fulfillment;
    }

    @GetMapping("/seller/orders")
    @Operation(summary = "Listar subórdenes pagadas del vendedor autenticado")
    ResponseEntity<SellerOrderPage> sellerOrders(@AuthenticationPrincipal AccountResponse account,
            @RequestParam(required = false) SellerOrderStatus status,
            @RequestParam(required = false) UUID subOrderId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return privateResponse(fulfillment.sellerOrders(account, status, subOrderId, page, size));
    }

    @PostMapping("/seller/sub-orders/{id}/ship")
    @Operation(summary = "Registrar el despacho de una suborden propia del vendedor")
    ResponseEntity<ShipmentResponse> ship(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
            @Valid @RequestBody ShipRequest request, HttpServletResponse response) {
        return privateResponse(fulfillment.ship(account, id, request, responseTraceId(response)));
    }

    @PostMapping("/me/sub-orders/{id}/confirm-delivery")
    @Operation(summary = "Confirmar que el comprador recibió una suborden propia")
    ResponseEntity<ShipmentResponse> confirmDelivery(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
            @Valid @RequestBody DeliveryConfirmRequest request, HttpServletResponse response) {
        return privateResponse(fulfillment.confirmDelivery(account.id(), id, request, responseTraceId(response)));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
