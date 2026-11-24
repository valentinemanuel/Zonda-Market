package com.zondamarket.cart.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.cart.dto.CartDtos.CartItemRequest;
import com.zondamarket.cart.dto.CartDtos.CartResponse;
import com.zondamarket.cart.service.CartService;
import com.zondamarket.checkout.service.ReservationExpiry;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Carrito")
@SecurityRequirement(name = "bearerAuth")
class CartController {
    private final CartService cart;
    private final ReservationExpiry expiry;

    CartController(CartService cart, ReservationExpiry expiry) {
        this.cart = cart;
        this.expiry = expiry;
    }

    @GetMapping
    @Operation(summary = "Cotizar el carrito de la cuenta con precios y disponibilidad vigentes")
    ResponseEntity<CartResponse> get(@AuthenticationPrincipal AccountResponse account, HttpServletResponse http) {
        expiry.forBuyer(account.id(), responseTraceId(http));
        return response(cart.get(account.id()));
    }

    @PutMapping("/items/{skuId}")
    @Operation(summary = "Establecer la cantidad de una variante en el carrito propio")
    ResponseEntity<CartResponse> put(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID skuId,
                                     @Valid @RequestBody CartItemRequest request, HttpServletResponse http) {
        UUID trace = responseTraceId(http);
        expiry.forBuyer(account.id(), trace);
        expiry.forSku(skuId, trace);
        return response(cart.put(account.id(), skuId, request.quantity().intValueExact(),
                request.expectedVersion().longValueExact()));
    }

    @DeleteMapping("/items/{skuId}")
    @Operation(summary = "Quitar una variante del carrito propio")
    ResponseEntity<CartResponse> remove(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID skuId,
                                        @RequestParam @Min(0) long expectedVersion, HttpServletResponse http) {
        expiry.forBuyer(account.id(), responseTraceId(http));
        return response(cart.remove(account.id(), skuId, expectedVersion));
    }

    @PostMapping("/reorders/{orderId}")
    @Operation(summary = "Agregar al carrito los productos de una compra propia")
    ResponseEntity<CartResponse> reorder(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID orderId,
                                         @RequestHeader("Idempotency-Key") UUID key, HttpServletResponse http) {
        expiry.forBuyer(account.id(), responseTraceId(http));
        return response(cart.reorder(account.id(), orderId, key));
    }

    private static ResponseEntity<CartResponse> response(CartResponse value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
