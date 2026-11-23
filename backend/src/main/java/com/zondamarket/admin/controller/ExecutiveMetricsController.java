package com.zondamarket.admin.controller;

import com.zondamarket.admin.dto.ExecutiveMetricsResponse;
import com.zondamarket.admin.service.ExecutiveMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
class ExecutiveMetricsController {
    private final ExecutiveMetricsService reports;

    ExecutiveMetricsController(ExecutiveMetricsService reports) {
        this.reports = reports;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Consultar las métricas globales de operación")
    ResponseEntity<ExecutiveMetricsResponse> dashboard(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.dashboard(from, to));
    }

    @GetMapping(value = "/reports/sales.csv", produces = "text/csv")
    @Operation(summary = "Exportar las métricas globales en CSV")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        var report = reports.export(from, to);
        var disposition = ContentDisposition.attachment().filename(report.filename()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(report.content().getBytes(StandardCharsets.UTF_8));
    }
}
