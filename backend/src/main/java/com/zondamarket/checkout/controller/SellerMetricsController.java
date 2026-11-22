package com.zondamarket.checkout.controller;

import com.zondamarket.checkout.dto.SellerMetricsDtos.SellerMetricsResponse;
import com.zondamarket.checkout.dto.SellerMetricsDtos.SettlementPage;
import com.zondamarket.checkout.service.SellerMetricsService;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@Tag(name = "Ventas del vendedor")
@SecurityRequirement(name = "bearerAuth")
class SellerMetricsController {
    private final SellerMetricsService sales;

    SellerMetricsController(SellerMetricsService sales) {
        this.sales = sales;
    }

    @GetMapping("/metrics")
    @Operation(summary = "Consultar los importes y subórdenes propias de un período")
    ResponseEntity<SellerMetricsResponse> metrics(@AuthenticationPrincipal AccountResponse account,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sales.metrics(account.id(), from, to));
    }

    @GetMapping("/settlements")
    @Operation(summary = "Consultar la actividad registrada por suborden propia")
    ResponseEntity<SettlementPage> settlements(@AuthenticationPrincipal AccountResponse account,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sales.settlements(account.id(), from, to, page, size));
    }
}
