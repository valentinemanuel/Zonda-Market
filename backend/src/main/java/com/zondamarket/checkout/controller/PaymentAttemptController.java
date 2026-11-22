package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.PaymentDtos.PaymentAttemptResponse;
import com.zondamarket.checkout.dto.PaymentDtos.PaymentOptions;
import com.zondamarket.checkout.dto.PaymentDtos.StartRequest;
import com.zondamarket.checkout.dto.CardPaymentRequest;
import com.zondamarket.checkout.dto.ProPaymentRequest;
import com.zondamarket.checkout.service.CardPaymentService;
import com.zondamarket.checkout.service.CheckoutProService;
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
    private final CardPaymentService cards;
    private final CheckoutProService pro;

    PaymentAttemptController(PaymentAttemptService payments, CardPaymentService cards, CheckoutProService pro) {
        this.payments = payments;
        this.cards = cards;
        this.pro = pro;
    }

    @GetMapping("/payment-options")
    ResponseEntity<PaymentOptions> options(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id) {
        return privateResponse(payments.options(account.id(), id, account.email()));
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

    @PostMapping("/payments/card")
    ResponseEntity<PaymentAttemptResponse> card(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
            @Valid @RequestBody CardPaymentRequest request, HttpServletResponse response,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key) {
        return privateResponse(cards.submit(account.id(), id, key, request, trace(response)));
    }

    @PostMapping("/payments/{attemptId}/reconcile-card")
    ResponseEntity<PaymentAttemptResponse> reconcileCard(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, @PathVariable UUID attemptId, HttpServletResponse response) {
        return privateResponse(cards.consult(account.id(), id, attemptId, trace(response)));
    }

    @PostMapping("/payments/pro")
    ResponseEntity<PaymentAttemptResponse> checkoutPro(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, @Valid @RequestBody ProPaymentRequest request, HttpServletResponse response,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key) {
        return privateResponse(pro.submit(account.id(), id, request.attemptId(), key, trace(response)));
    }

    @PostMapping("/payments/{attemptId}/reconcile-pro")
    ResponseEntity<PaymentAttemptResponse> reconcilePro(@AuthenticationPrincipal AccountResponse account,
            @PathVariable UUID id, @PathVariable UUID attemptId, HttpServletResponse response) {
        return privateResponse(pro.consult(account.id(), id, attemptId, trace(response)));
    }

    private static UUID trace(HttpServletResponse response) {
        return UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
