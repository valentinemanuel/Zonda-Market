package com.zondamarket.catalog.dto;

import com.zondamarket.catalog.domain.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CategoryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID parentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, List<String>> attributeSchema
) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getParentId(), category.getAttributeSchema());
    }
}
