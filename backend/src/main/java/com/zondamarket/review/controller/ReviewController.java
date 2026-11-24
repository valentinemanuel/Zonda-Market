package com.zondamarket.review.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.review.dto.ReviewDtos.ReviewPage;
import com.zondamarket.review.dto.ReviewDtos.ReviewRequest;
import com.zondamarket.review.dto.ReviewDtos.ReviewResponse;
import com.zondamarket.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reseñas")
class ReviewController {
    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/me/sub-orders/{id}/review")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Calificar una suborden propia entregada")
    ResponseEntity<ReviewResponse> create(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest request, HttpServletResponse response) {
        UUID trace = responseTraceId(response);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reviews.create(account.id(), id, request, trace));
    }

    @GetMapping("/sellers/{slug}/reviews")
    @Operation(summary = "Consultar reseñas visibles y reputación pública de un vendedor")
    ReviewPage list(@PathVariable String slug, @RequestParam(defaultValue = "0") @Min(0) int page,
                    @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return reviews.forSeller(slug, page, size);
    }
}
