package com.zondamarket.identity.dto;

import com.zondamarket.identity.domain.AccountAddress;
import com.zondamarket.identity.domain.SellerProfile;
import com.zondamarket.identity.domain.SellerProfile.Type;
import com.zondamarket.review.dto.ReviewDtos.SellerReputationResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

public final class ProfileDtos {
    private static final String PROVINCE = "AR-[ABCDEFGHJKLMNPQRSTUVWXYZ]";
    private static final String POSTAL_CODE = "(?:[0-9]{4}|[A-Z][0-9]{4}[A-Z]{3})";
    private static final String SLUG = "[a-z0-9]+(?:-[a-z0-9]+)*";

    private ProfileDtos() {}

    public record ProfileUpdate(@NotBlank @Size(max = 80) String displayName) {
        public ProfileUpdate {
            displayName = strip(displayName);
        }
    }

    public record AddressRequest(
            @NotBlank @Size(max = 140) String street,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Pattern(regexp = PROVINCE) String provinceCode,
            @NotBlank @Pattern(regexp = POSTAL_CODE) String postalCode,
            @NotBlank @Pattern(regexp = "AR") String country
    ) {
        public AddressRequest {
            street = strip(street);
            city = strip(city);
            provinceCode = upper(provinceCode);
            postalCode = upper(postalCode);
            country = upper(country);
        }
    }

    public record AddressPatch(
            @Size(min = 1, max = 140) String street,
            @Size(min = 1, max = 80) String city,
            @Pattern(regexp = PROVINCE) String provinceCode,
            @Pattern(regexp = POSTAL_CODE) String postalCode,
            @Pattern(regexp = "AR") String country
    ) {
        public AddressPatch {
            street = strip(street);
            city = strip(city);
            provinceCode = upper(provinceCode);
            postalCode = upper(postalCode);
            country = upper(country);
        }
    }

    public record AddressResponse(UUID id, String street, String city, String provinceCode, String postalCode, String country) {
        public static AddressResponse from(AccountAddress address) {
            return new AddressResponse(address.getId(), address.getStreet(), address.getCity(),
                    address.getProvinceCode(), address.getPostalCode(), address.getCountry());
        }
    }

    public record SellerActivationRequest(
            @NotNull Type type,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = SLUG) String slug,
            @Size(max = 500) String bio
    ) {
        public SellerActivationRequest {
            displayName = strip(displayName);
            if (slug != null) {
                slug = Normalizer.normalize(slug.strip(), Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "-");
            }
            bio = bio == null ? "" : bio.strip();
        }
    }

    public record SellerResponse(Type type, String displayName, String slug, String bio,
                                 int reviewCount, BigDecimal averageRating) {
        public static SellerResponse from(SellerProfile seller) {
            return from(seller, SellerReputationResponse.EMPTY);
        }

        public static SellerResponse from(SellerProfile seller, SellerReputationResponse reputation) {
            return new SellerResponse(seller.getType(), seller.getDisplayName(), seller.getSlug(), seller.getBio(),
                    reputation.reviewCount(), reputation.averageRating());
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
