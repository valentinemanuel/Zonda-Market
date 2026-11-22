package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.PaymentDtos.PaymentAttemptResponse;
import com.zondamarket.checkout.dto.PaymentDtos.PaymentOptions;
import com.zondamarket.checkout.dto.PaymentDtos.StartRequest;
import com.zondamarket.checkout.service.PaymentAttemptService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
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
@RequestMapping("/api/v1/orders/{id}")
@Tag(name = "Pagos")
@SecurityRequirement(name = "bearerAuth")
class PaymentAttemptController {
    private final PaymentAttemptService payments;

    PaymentAttemptController(PaymentAttemptService payments) {
        this.payments = payments;
    }

    @GetMapping("/payment-options")
    ResponseEntity<PaymentOptions> options(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id) {
        return privateResponse(payments.options(account.id(), id));
    }

    @GetMapping("/payments/{attemptId}")
    ResponseEntity<PaymentAttemptResponse> attempt(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, @PathVariable UUID attemptId) {
        return privateResponse(payments.get(account.id(), id, attemptId));
    }

    @PostMapping("/payments")
    ResponseEntity<PaymentAttemptResponse> start(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, @Valid @RequestBody StartRequest request, HttpServletResponse response,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key) {
        return privateResponse(payments.start(account.id(), id, request.method(), key, trace(response)));
    }

    @PostMapping("/payments/{attemptId}/submit")
    ResponseEntity<PaymentAttemptResponse> submit(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, @PathVariable UUID attemptId, HttpServletResponse response) {
        return privateResponse(payments.submit(account.id(), id, attemptId, trace(response)));
    }

    private static UUID trace(HttpServletResponse response) {
        return UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
