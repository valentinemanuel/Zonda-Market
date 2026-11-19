package com.zondamarket.catalog.dto;

import com.zondamarket.catalog.domain.ListingImage;
import com.zondamarket.catalog.domain.ListingSku;
import com.zondamarket.identity.dto.ProfileDtos.SellerResponse;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ListingMediaDtos {
    private ListingMediaDtos() {}

    public record ImagePatch(@Size(max = 160) String alt,
                             @PositiveOrZero @Digits(integer = 1, fraction = 0) @Max(5) BigDecimal position) {}

    public record ImageResponse(UUID id, String url, String thumbnailUrl, String alt, int position) {
        public static ImageResponse from(ListingImage image) {
            return new ImageResponse(image.getId(), image.getUrl(), image.getThumbnailUrl(), image.getAlt(), image.getPosition());
        }
    }

    public record PublicSku(UUID id, Map<String, String> attributes, String price, boolean available) {
        public static PublicSku from(ListingSku sku) {
            return new PublicSku(sku.getId(), sku.getAttributes(), sku.getPrice().toPlainString(), sku.getAvailableStock() > 0);
        }
    }

    public record ListingDetailResponse(UUID id, String slug, String title, String description, Map<String, Object> specs,
                                        CategoryResponse category, SellerResponse seller, List<ImageResponse> images,
                                        List<PublicSku> skus) {}
}
