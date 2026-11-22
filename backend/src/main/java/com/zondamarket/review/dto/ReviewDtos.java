package com.zondamarket.review.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewDtos {
    private ReviewDtos() {}

    public record ReviewRequest(@NotNull @Min(1) @Max(5) @Digits(integer = 1, fraction = 0) BigDecimal rating,
                                @Size(max = 1000) String comment) {}

    public record ReviewResponse(UUID id, int rating, String comment, Instant createdAt, String authorAlias) {}

    public record SellerReputationResponse(BigDecimal averageRating, int reviewCount) {
        public static final SellerReputationResponse EMPTY = new SellerReputationResponse(null, 0);
    }

    public record ReviewPage(List<ReviewResponse> content, int page, int size, boolean hasNext,
                             SellerReputationResponse reputation) {}
}
