package com.zondamarket.catalog.controller;

import com.zondamarket.catalog.dto.ListingMediaDtos.ListingDetailResponse;
import com.zondamarket.catalog.service.ListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Catálogo público")
class PublicListingController {
    private final ListingService listings;

    PublicListingController(ListingService listings) {
        this.listings = listings;
    }

    @GetMapping("/api/v1/listings/{slug}")
    @Operation(summary = "Consultar una ficha publicada por slug")
    ResponseEntity<ListingDetailResponse> get(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listings.publicListing(slug));
    }
}
