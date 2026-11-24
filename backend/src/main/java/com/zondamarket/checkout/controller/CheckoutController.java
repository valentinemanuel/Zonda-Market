package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.CheckoutDtos.ConfirmRequest;
import com.zondamarket.checkout.dto.CheckoutDtos.DirectPreview;
import com.zondamarket.checkout.dto.CheckoutDtos.OrderPage;
import com.zondamarket.checkout.dto.CheckoutDtos.HistoryFilter;
import jakarta.validation.constraints.Size;
import com.zondamarket.checkout.dto.CheckoutDtos.OrderResponse;
import com.zondamarket.checkout.dto.CheckoutDtos.QuoteRequest;
import com.zondamarket.checkout.dto.CheckoutDtos.QuoteResponse;
import com.zondamarket.checkout.service.CheckoutService;
import com.zondamarket.checkout.service.ReservationExpiry;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Checkout")
@SecurityRequirement(name = "bearerAuth")
class CheckoutController {
    private final CheckoutService checkout;
    private final ReservationExpiry expiry;

    CheckoutController(CheckoutService checkout, ReservationExpiry expiry) {
        this.checkout = checkout;
        this.expiry = expiry;
    }

    @GetMapping("/checkout/items/{skuId}")
    @Operation(summary = "Consultar la variante seleccionada para una compra directa")
    ResponseEntity<DirectPreview> directPreview(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID skuId, @RequestParam(defaultValue = "1") @Min(1) @Max(99) int quantity,
            HttpServletResponse response) {
        expiry.forSku(skuId, trace(response));
        return privateResponse(checkout.directPreview(account.id(), skuId, quantity));
    }

    @PostMapping("/checkout/quote")
    @Operation(summary = "Cotizar el carrito propio para una dirección de la cuenta")
    ResponseEntity<QuoteResponse> quote(@AuthenticationPrincipal AccountResponse account,
            @Valid @RequestBody QuoteRequest request, HttpServletResponse response) {
        if (account.emailVerified()) {
            if (request.directSkuId() != null) expiry.forSku(request.directSkuId(), trace(response));
            expiry.forCheckout(account.id(), trace(response));
        }
        int quantity = request.quantity() == null ? 1 : request.quantity().intValueExact();
        return privateResponse(checkout.quote(account, request.addressId(), request.cartVersion().longValueExact(),
                request.directSkuId(), quantity));
    }

    @PostMapping("/checkout/confirm")
    @Operation(summary = "Confirmar una cotización propia y reservar el inventario")
    ResponseEntity<OrderResponse> confirm(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody ConfirmRequest request,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key, HttpServletResponse response) {
        var trace = trace(response);
        if (account.emailVerified()) expiry.forCheckout(account.id(), trace);
        return privateResponse(checkout.confirm(account, request.quoteId(), key, trace));
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Consultar el detalle congelado de una orden propia")
    ResponseEntity<OrderResponse> order(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, HttpServletResponse response) {
        expiry.forOrder(account.id(), id, trace(response));
        return privateResponse(checkout.get(account.id(), id));
    }

    @PostMapping("/orders/{id}/cancel-reservation")
    @Operation(summary = "Liberar una reserva propia antes de enviar el pago")
    ResponseEntity<Void> cancelReservation(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, HttpServletResponse response) {
        expiry.forOrder(account.id(), id, trace(response));
        checkout.cancelReservation(account.id(), id, trace(response));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/orders/active")
    @Operation(summary = "Recuperar la reserva de la cuenta")
    ResponseEntity<OrderResponse> active(@AuthenticationPrincipal AccountResponse account, HttpServletResponse response) {
        expiry.forBuyer(account.id(), trace(response));
        return checkout.active(account.id()).map(CheckoutController::privateResponse)
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @GetMapping("/orders")
    @Operation(summary = "Listar las órdenes de la cuenta desde la más reciente")
    ResponseEntity<OrderPage> history(@AuthenticationPrincipal AccountResponse account,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(required = false) HistoryFilter filter, HttpServletResponse response) {
        expiry.forBuyer(account.id(), trace(response));
        return privateResponse(checkout.history(account.id(), page, size, q.strip(), filter));
    }

    private static UUID trace(HttpServletResponse response) {
        return UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
