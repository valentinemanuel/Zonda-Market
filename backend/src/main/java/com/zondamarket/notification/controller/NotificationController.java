package com.zondamarket.notification.controller;

import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.notification.dto.NotificationDtos.NotificationPage;
import com.zondamarket.notification.dto.NotificationDtos.NotificationResponse;
import com.zondamarket.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notifications")
@Tag(name = "Notificaciones")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {
    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "Listar los avisos de la cuenta y contar los pendientes de lectura")
    ResponseEntity<NotificationPage> list(@AuthenticationPrincipal AccountResponse account,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.list(account.id(), page, size));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Marcar una notificación propia como leída")
    ResponseEntity<NotificationResponse> markRead(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.markRead(account.id(), id));
    }
}
