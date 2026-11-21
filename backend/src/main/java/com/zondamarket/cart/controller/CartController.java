package com.zondamarket.cart.controller;

import com.zondamarket.cart.dto.CartDtos.CartItemRequest;
import com.zondamarket.cart.dto.CartDtos.CartResponse;
import com.zondamarket.cart.service.CartService;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Carrito")
@SecurityRequirement(name = "bearerAuth")
class CartController {
    private final CartService cart;

    CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    @Operation(summary = "Cotizar el carrito de la cuenta con precios y disponibilidad vigentes")
    ResponseEntity<CartResponse> get(@AuthenticationPrincipal AccountResponse account) {
        return response(cart.get(account.id()));
    }

    @PutMapping("/items/{skuId}")
    @Operation(summary = "Establecer la cantidad de una variante en el carrito propio")
    ResponseEntity<CartResponse> put(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID skuId,
                                     @Valid @RequestBody CartItemRequest request) {
        return response(cart.put(account.id(), skuId, request.quantity().intValueExact(),
                request.expectedVersion().longValueExact()));
    }

    @DeleteMapping("/items/{skuId}")
    @Operation(summary = "Quitar una variante del carrito propio")
    ResponseEntity<CartResponse> remove(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID skuId,
                                        @RequestParam @Min(0) long expectedVersion) {
        return response(cart.remove(account.id(), skuId, expectedVersion));
    }

    private static ResponseEntity<CartResponse> response(CartResponse value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
