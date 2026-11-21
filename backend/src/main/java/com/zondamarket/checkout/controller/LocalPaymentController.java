package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.CheckoutDtos.OrderResponse;
import com.zondamarket.checkout.service.CheckoutService;
import com.zondamarket.checkout.service.LocalPaymentPort;
import com.zondamarket.checkout.service.LocalPaymentPort.Outcome;
import com.zondamarket.checkout.service.PaymentConfirmationService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile(LocalPaymentPort.PROFILES)
@Tag(name = "Simulación local")
@SecurityRequirement(name = "bearerAuth")
class LocalPaymentController {
    private final LocalPaymentPort local;
    private final PaymentConfirmationService payment;
    private final CheckoutService checkout;

    LocalPaymentController(LocalPaymentPort local, PaymentConfirmationService payment, CheckoutService checkout) {
        this.local = local;
        this.payment = payment;
        this.checkout = checkout;
    }

    record Request(@NotNull Outcome outcome, @NotNull @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String reference) {}

    @PostMapping("/api/v1/test-fixtures/orders/{id}/payment-outcome")
    ResponseEntity<OrderResponse> outcome(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
                                           @Valid @RequestBody Request request, HttpServletResponse response) {
        var verified = local.verify(account.id(), id, request.reference(), request.outcome());
        var trace = UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
        payment.applyVerifiedOutcome(account.id(), id, verified, trace);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checkout.get(account.id(), id));
    }
}
