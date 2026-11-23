package com.zondamarket.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiscoveryDtos {
    private DiscoveryDtos() {}

    public enum Sort { relevance, newest, price_asc, price_desc }
    public enum ConditionFilter { nuevo, usado }

    public record Search(String q, UUID category, BigDecimal min, BigDecimal max, String seller,
                         Map<String, String> attributes, Boolean inStock, ConditionFilter condition,
                         int page, int size, Sort sort) {}

    public record ListingCardResponse(UUID id, String slug, String title, UUID categoryId,
                                      String seller, String sellerName, String thumbnailUrl,
                                      String price, boolean available, String condition, UUID quickSkuId) {}

    public record AttributeFacet(String key, String value, long count) {}
    public record SellerFacet(String slug, String name, long count) {}

    public record ListingPage(List<ListingCardResponse> content, int page, int size, long totalElements,
                              long totalPages, List<AttributeFacet> facets, List<SellerFacet> sellers) {}

    public record CategoryNode(CategoryResponse category, List<CategoryNode> children) {}
}
