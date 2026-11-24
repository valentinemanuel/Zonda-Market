package com.zondamarket.catalog.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.catalog.dto.StockDtos.AdjustmentRequest;
import com.zondamarket.catalog.dto.StockDtos.Movement;
import com.zondamarket.catalog.dto.StockDtos.MovementPage;
import com.zondamarket.catalog.service.StockService;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
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
@RequestMapping("/api/v1/seller/skus/{id}")
@Tag(name = "Inventario del vendedor")
@SecurityRequirement(name = "bearerAuth")
class StockController {
    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @PostMapping("/stock-adjustments")
    @Operation(summary = "Ajustar el stock disponible de un SKU propio")
    Movement adjust(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
                    @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key,
                    @Valid @RequestBody AdjustmentRequest request, HttpServletResponse response) {
        UUID trace = responseTraceId(response);
        return stock.adjust(account.id(), id, key, request, trace);
    }

    @GetMapping("/stock-movements")
    @Operation(summary = "Listar los movimientos más recientes de un SKU propio")
    MovementPage movements(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
                           @RequestParam(defaultValue = "0") @Min(0) int page,
                           @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return stock.history(account.id(), id, page, size);
    }
}
