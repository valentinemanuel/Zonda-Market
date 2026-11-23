package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.DisputeDtos.*;
import com.zondamarket.checkout.service.DisputeService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Reclamos privados")
@SecurityRequirement(name = "bearerAuth")
class DisputeController {
    private final DisputeService disputes;

    DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @PostMapping("/me/sub-orders/{id}/disputes")
    ResponseEntity<Reference> open(@AuthenticationPrincipal AccountResponse actor, @PathVariable UUID id,
            @Valid @RequestBody OpenRequest request, HttpServletResponse response) {
        return privateResponse(HttpStatus.CREATED, disputes.open(actor.id(), id, request, trace(response)));
    }

    @GetMapping("/disputes/{id}")
    ResponseEntity<Detail> detail(@AuthenticationPrincipal AccountResponse actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return privateResponse(HttpStatus.OK, disputes.detail(actor, id, page, size));
    }

    @PostMapping("/disputes/{id}/messages")
    ResponseEntity<Message> message(@AuthenticationPrincipal AccountResponse actor, @PathVariable UUID id,
            @Valid @RequestBody MessageRequest request, @RequestHeader(name = "Idempotency-Key", required = false) UUID key) {
        return privateResponse(HttpStatus.CREATED, disputes.message(actor, id, request, key));
    }

    @GetMapping("/admin/disputes")
    ResponseEntity<CasePage> queue(@RequestParam(required = false) Status status,
            @RequestParam(required = false) @Min(1) @Max(365) Integer maxAgeDays,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return privateResponse(HttpStatus.OK, disputes.queue(status, maxAgeDays, page, size));
    }

    @PostMapping("/admin/disputes/{id}/assign")
    ResponseEntity<Reference> assign(@AuthenticationPrincipal AccountResponse actor, @PathVariable UUID id,
            HttpServletResponse response) {
        return privateResponse(HttpStatus.OK, disputes.assign(actor, id, trace(response)));
    }

    private static UUID trace(HttpServletResponse response) {
        return UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
    }

    private static <T> ResponseEntity<T> privateResponse(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
