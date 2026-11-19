package com.zondamarket.catalog.service;

import com.zondamarket.catalog.domain.Category;
import com.zondamarket.catalog.domain.Listing;
import com.zondamarket.catalog.domain.ListingSku;
import com.zondamarket.catalog.dto.CategoryResponse;
import com.zondamarket.catalog.dto.ListingDtos.ListingResponse;
import com.zondamarket.catalog.dto.ListingDtos.ListingSummary;
import com.zondamarket.catalog.dto.ListingDtos.PublicationPatch;
import com.zondamarket.catalog.dto.ListingDtos.PublicationRequest;
import com.zondamarket.catalog.dto.ListingDtos.SkuPatch;
import com.zondamarket.catalog.dto.ListingDtos.SkuRequest;
import com.zondamarket.catalog.dto.ListingDtos.SkuResponse;
import com.zondamarket.catalog.repository.CategoryRepository;
import com.zondamarket.catalog.repository.ListingRepository;
import com.zondamarket.catalog.repository.ListingSkuRepository;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.repository.SellerProfileRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ListingService {
    private final ListingRepository listings;
    private final ListingSkuRepository skus;
    private final CategoryRepository categories;
    private final SellerProfileRepository sellers;
    private final ObjectMapper json;

    ListingService(ListingRepository listings, ListingSkuRepository skus, CategoryRepository categories,
                   SellerProfileRepository sellers, ObjectMapper json) {
        this.listings = listings;
        this.skus = skus;
        this.categories = categories;
        this.sellers = sellers;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories(UUID accountId) {
        sellerId(accountId);
        return categories.findLeaves().stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ListingSummary> list(UUID accountId) {
        return listings.findAllBySellerIdAndDeletedFalseOrderByCreatedAtDescIdAsc(sellerId(accountId))
                .stream().map(ListingSummary::from).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ListingResponse get(UUID accountId, UUID id) {
        Listing listing = listings.findByIdAndSellerIdAndDeletedFalse(id, sellerId(accountId))
                .orElseThrow(ListingService::missingListing);
        return ListingResponse.from(listing, listingSkus(id));
    }

    @Transactional
    public ListingResponse create(UUID accountId, PublicationRequest request) {
        UUID seller = sellerId(accountId);
        requireLeaf(request.categoryId());
        validateListing(request.title(), request.description(), request.specs());
        return ListingResponse.from(listings.save(new Listing(seller, request.categoryId(), request.title(),
                request.description(), request.specs())), List.of());
    }

    @Transactional
    public ListingResponse edit(UUID accountId, UUID id, PublicationPatch request) {
        Listing listing = lockListing(accountId, id);
        requireVersion(request.expectedVersion().longValueExact(), listing.getVersion());

        UUID categoryId = orCurrent(request.categoryId(), listing.getCategoryId());
        Category category = requireLeaf(categoryId);
        String title = orCurrent(request.title(), listing.getTitle());
        String description = orCurrent(request.description(), listing.getDescription());
        Map<String, Object> specs = orCurrent(request.specs(), listing.getSpecs());
        validateListing(title, description, specs);

        List<ListingSku> variants = listingSkus(id);
        if (!categoryId.equals(listing.getCategoryId())) {
            for (ListingSku variant : variants) {
                if (!matchesSchema(category, variant.getAttributes())) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "CATEGORY_SKUS_INCOMPATIBLE",
                            "La nueva categoría debe admitir todas las variantes guardadas.");
                }
            }
        }
        listing.edit(categoryId, title, description, specs);
        try {
            listings.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw staleVersion();
        }
        return ListingResponse.from(listing, variants);
    }

    @Transactional
    public SkuResponse addSku(UUID accountId, UUID id, SkuRequest request) {
        Listing listing = lockListing(accountId, id);
        Map<String, String> attributes = validateAttributes(listing, request.attributes());
        return saveSku(new ListingSku(id, request.code().toUpperCase(Locale.ROOT),
                attributes, request.price(), request.initialStock().intValueExact()));
    }

    @Transactional
    public SkuResponse editSku(UUID accountId, UUID id, UUID skuId, SkuPatch request) {
        Listing listing = lockListing(accountId, id);
        ListingSku sku = skus.findByIdAndListingIdAndDeletedFalse(skuId, id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND",
                        "No encontramos esa variante."));
        requireVersion(request.expectedVersion().longValueExact(), sku.getVersion());
        Map<String, ?> updatedAttributes = request.attributes() == null ? sku.getAttributes() : request.attributes();
        Map<String, String> attributes = validateAttributes(listing, updatedAttributes);
        sku.edit(attributes, orCurrent(request.price(), sku.getPrice()));
        return saveSku(sku);
    }

    private SkuResponse saveSku(ListingSku sku) {
        try {
            return SkuResponse.from(skus.saveAndFlush(sku));
        } catch (OptimisticLockingFailureException exception) {
            throw staleVersion();
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation) {
                if ("listing_sku_code_unique".equals(violation.getConstraintName())) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "SKU_CODE_IN_USE", "Ese código SKU ya está en uso.");
                }
                if ("listing_sku_combination_unique".equals(violation.getConstraintName())) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "SKU_COMBINATION_IN_USE",
                            "Esa combinación de atributos ya existe en esta publicación.");
                }
            }
            throw exception;
        }
    }

    private UUID sellerId(UUID accountId) {
        return sellers.findByAccountIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(accountId, Role.SELLER)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.FORBIDDEN, "SELLER_REQUIRED",
                        "Activá tu perfil de vendedor para gestionar publicaciones.")).getId();
    }

    private Listing lockListing(UUID accountId, UUID id) {
        return listings.findLockedByIdAndSellerIdAndDeletedFalse(id, sellerId(accountId))
                .orElseThrow(ListingService::missingListing);
    }

    private List<ListingSku> listingSkus(UUID listingId) {
        return skus.findAllByListingIdAndDeletedFalseOrderByCreatedAtAscIdAsc(listingId);
    }

    private Category requireLeaf(UUID categoryId) {
        return categories.findLeaf(categoryId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.BAD_REQUEST, "CATEGORY_NOT_LEAF", "Elegí una categoría final disponible."));
    }

    private void validateListing(String title, String description, Map<String, Object> specs) {
        if (title.isBlank() || description.isBlank() || title.indexOf(0) >= 0 || description.indexOf(0) >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "LISTING_TEXT_INVALID",
                    "Ingresá un título y una descripción válidos.");
        }
        if (json.writeValueAsBytes(specs).length > 8192) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SPECS_TOO_LARGE",
                    "Las especificaciones admiten hasta 8 KiB de JSON.");
        }
        if (containsInvalidText(specs)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SPECS_INVALID", "Revisá las especificaciones.");
        }
    }

    private static boolean containsInvalidText(Object value) {
        return switch (value) {
            case String text -> text.indexOf(0) >= 0;
            case Map<?, ?> map -> map.entrySet().stream()
                    .anyMatch(entry -> containsInvalidText(entry.getKey()) || containsInvalidText(entry.getValue()));
            case List<?> list -> list.stream().anyMatch(ListingService::containsInvalidText);
            case null, default -> false;
        };
    }

    private Map<String, String> validateAttributes(Listing listing, Map<String, ?> attributes) {
        if (json.writeValueAsBytes(attributes).length > 1024 || !matchesSchema(requireLeaf(listing.getCategoryId()), attributes)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SKU_ATTRIBUTES_INVALID",
                    "La combinación debe usar todos los atributos y valores permitidos por la categoría.");
        }
        return attributes.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> (String) entry.getValue()));
    }

    private static boolean matchesSchema(Category category, Map<String, ?> attributes) {
        var schema = category.getAttributeSchema();
        return schema.keySet().equals(attributes.keySet())
                && schema.entrySet().stream().allMatch(field -> attributes.get(field.getKey()) instanceof String value
                        && field.getValue().contains(value));
    }

    private static <T> T orCurrent(T update, T current) {
        return update == null ? current : update;
    }

    private static void requireVersion(long expected, long current) {
        if (expected != current) {
            throw staleVersion();
        }
    }

    private static ApiProblemException missingListing() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "No encontramos esa publicación.");
    }

    private static ApiProblemException staleVersion() {
        return new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "Hay una versión más reciente. Recargá la publicación para seguir editando.");
    }
}
