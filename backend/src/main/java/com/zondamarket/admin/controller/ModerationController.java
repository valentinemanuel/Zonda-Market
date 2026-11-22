package com.zondamarket.admin.controller;

import com.zondamarket.admin.dto.ModerationDtos.*;
import com.zondamarket.admin.repository.ModerationStore.Target;
import com.zondamarket.admin.service.ModerationService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Moderación")
@SecurityRequirement(name = "bearerAuth")
class ModerationController {
    private final ModerationService moderation;

    ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/listings")
    ResponseEntity<Page<ListingRow>> listings(@RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(defaultValue = "") @Pattern(regexp = "VISIBLE|HIDDEN|") String state,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return privateResponse(moderation.listings(q, state, page, size));
    }

    @GetMapping("/users")
    ResponseEntity<Page<AccountRow>> users(@RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(defaultValue = "") @Pattern(regexp = "ACTIVE|SUSPENDED|") String state,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return privateResponse(moderation.accounts(q, state, page, size));
    }

    @GetMapping("/{resource:listings|users}/{id}/history")
    ResponseEntity<Page<Audit>> history(@PathVariable String resource, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return privateResponse(moderation.history(target(resource), id, page, size));
    }

    @GetMapping("/{resource:listings|users}/{id}/cases")
    ResponseEntity<Page<OrderCase>> cases(@PathVariable String resource, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return privateResponse(moderation.cases(target(resource), id, page, size));
    }

    @PostMapping("/listings/{id}/moderation")
    ResponseEntity<Audit> moderate(@PathVariable UUID id, @Valid @RequestBody Command<ListingAction> command,
            @AuthenticationPrincipal AccountResponse actor, HttpServletResponse response) {
        return privateResponse(moderation.moderate(id, command, actor, trace(response)));
    }

    @PostMapping("/users/{id}/status")
    ResponseEntity<Audit> status(@PathVariable UUID id, @Valid @RequestBody Command<AccountAction> command,
            @AuthenticationPrincipal AccountResponse actor, HttpServletResponse response) {
        return privateResponse(moderation.changeStatus(id, command, actor, trace(response)));
    }

    private static Target target(String resource) {
        return "listings".equals(resource) ? Target.LISTING : Target.ACCOUNT;
    }

    private static UUID trace(HttpServletResponse response) {
        return UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
