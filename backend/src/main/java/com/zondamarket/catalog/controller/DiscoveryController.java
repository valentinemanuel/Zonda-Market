package com.zondamarket.catalog.controller;

import com.zondamarket.catalog.dto.DiscoveryDtos.ConditionFilter;
import com.zondamarket.catalog.dto.DiscoveryDtos.ListingPage;
import com.zondamarket.catalog.dto.DiscoveryDtos.Sort;
import com.zondamarket.catalog.service.DiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Catálogo público")
class DiscoveryController {
    private final DiscoveryService discovery;

    DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/api/v1/listings")
    @Operation(summary = "Buscar publicaciones visibles",
            parameters = @Parameter(name = "attr", description = "Repetí attr=nombre:valor para combinar atributos del mismo SKU.",
                    array = @ArraySchema(maxItems = 16, schema = @Schema(type = "string", maxLength = 241))))
    ResponseEntity<ListingPage> search(
            @RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(required = false) UUID category,
            @RequestParam(required = false) @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal min,
            @RequestParam(required = false) @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal max,
            @RequestParam(required = false) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") @Size(max = 50) String seller,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) ConditionFilter condition,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(48) int size,
            @RequestParam(defaultValue = "relevance") Sort sort) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                discovery.search(q, category, min, max, seller, params.getOrDefault("attr", List.of()),
                        inStock, condition, page, size, sort));
    }
}
