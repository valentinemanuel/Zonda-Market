package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.CheckoutDtos.ConfirmRequest;
import com.zondamarket.checkout.dto.CheckoutDtos.OrderResponse;
import com.zondamarket.checkout.dto.CheckoutDtos.QuoteRequest;
import com.zondamarket.checkout.dto.CheckoutDtos.QuoteResponse;
import com.zondamarket.checkout.service.CheckoutService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Checkout")
@SecurityRequirement(name = "bearerAuth")
class CheckoutController {
    private final CheckoutService checkout;

    CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/checkout/quote")
    @Operation(summary = "Cotizar el carrito propio para una dirección de la cuenta")
    ResponseEntity<QuoteResponse> quote(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody QuoteRequest request) {
        return privateResponse(checkout.quote(account, request.addressId(), request.cartVersion().longValueExact()));
    }

    @PostMapping("/checkout/confirm")
    @Operation(summary = "Confirmar una cotización propia y reservar el inventario")
    ResponseEntity<OrderResponse> confirm(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody ConfirmRequest request,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key, HttpServletResponse response) {
        var trace = UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
        return privateResponse(checkout.confirm(account, request.quoteId(), key, trace));
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Consultar el detalle congelado de una orden propia")
    ResponseEntity<OrderResponse> order(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id) {
        return privateResponse(checkout.get(account.id(), id));
    }

    @GetMapping("/orders/active")
    @Operation(summary = "Recuperar la reserva de la cuenta")
    ResponseEntity<OrderResponse> active(@AuthenticationPrincipal AccountResponse account) {
        return checkout.active(account.id()).map(CheckoutController::privateResponse)
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
