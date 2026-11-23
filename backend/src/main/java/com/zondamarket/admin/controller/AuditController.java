package com.zondamarket.admin.controller;

import com.zondamarket.admin.dto.AuditDtos.*;
import com.zondamarket.admin.service.AuditService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
class AuditController {
    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit-logs")
    ResponseEntity<Page<AuditEventResponse>> events(
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{1,40}") String action,
            @RequestParam(required = false) UUID resource,
            @RequestParam(required = false) UUID traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(audit.events(actor, action, resource, traceId, from, to, page, size));
    }

    @GetMapping("/payment-incidents")
    ResponseEntity<Page<PaymentIncident>> incidents(
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(audit.incidents(page, size));
    }

    @PostMapping("/payment-incidents/{id}/reconcile")
    ResponseEntity<RecoveryResponse> reconcile(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ReconcileRequest request, @AuthenticationPrincipal AccountResponse actor,
            HttpServletResponse response) {
        var trace = UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(audit.reconcile(id, key, actor.id(), request.reason().strip(), trace));
    }
}
