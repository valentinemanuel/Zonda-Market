package com.zondamarket.catalog.controller;

import com.zondamarket.catalog.dto.CategoryResponse;
import com.zondamarket.catalog.dto.ListingDtos.ListingResponse;
import com.zondamarket.catalog.dto.ListingDtos.ListingSummary;
import com.zondamarket.catalog.dto.ListingDtos.PublicationPatch;
import com.zondamarket.catalog.dto.ListingDtos.PublicationRequest;
import com.zondamarket.catalog.dto.ListingDtos.SkuPatch;
import com.zondamarket.catalog.dto.ListingDtos.SkuRequest;
import com.zondamarket.catalog.dto.ListingDtos.SkuResponse;
import com.zondamarket.catalog.service.ListingService;
import com.zondamarket.identity.dto.AccountResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@Tag(name = "Publicaciones del vendedor")
@SecurityRequirement(name = "bearerAuth")
class ListingController {
    private final ListingService listings;

    ListingController(ListingService listings) {
        this.listings = listings;
    }

    @GetMapping("/listing-categories")
    List<CategoryResponse> categories(@AuthenticationPrincipal AccountResponse account) {
        return listings.categories(account.id());
    }

    @GetMapping("/listings")
    List<ListingSummary> list(@AuthenticationPrincipal AccountResponse account) {
        return listings.list(account.id());
    }

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    ListingResponse create(@AuthenticationPrincipal AccountResponse account, @Valid @RequestBody PublicationRequest request) {
        return listings.create(account.id(), request);
    }

    @GetMapping("/listings/{id}")
    ListingResponse get(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id) {
        return listings.get(account.id(), id);
    }

    @PatchMapping("/listings/{id}")
    ListingResponse edit(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
                         @Valid @RequestBody PublicationPatch request) {
        return listings.edit(account.id(), id, request);
    }

    @PostMapping("/listings/{id}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    SkuResponse addSku(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id,
                       @Valid @RequestBody SkuRequest request) {
        return listings.addSku(account.id(), id, request);
    }

    @PatchMapping("/listings/{id}/skus/{skuId}")
    SkuResponse editSku(@AuthenticationPrincipal AccountResponse account, @PathVariable UUID id, @PathVariable UUID skuId,
                        @Valid @RequestBody SkuPatch request) {
        return listings.editSku(account.id(), id, skuId, request);
    }
}
