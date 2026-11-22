package com.zondamarket.identity.service;

import com.zondamarket.identity.domain.AccountAddress;
import com.zondamarket.identity.domain.SellerProfile;
import com.zondamarket.identity.domain.UserAccount;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.dto.ProfileDtos.AddressPatch;
import com.zondamarket.identity.dto.ProfileDtos.AddressRequest;
import com.zondamarket.identity.dto.ProfileDtos.AddressResponse;
import com.zondamarket.identity.dto.ProfileDtos.ProfileUpdate;
import com.zondamarket.identity.dto.ProfileDtos.SellerActivationRequest;
import com.zondamarket.identity.dto.ProfileDtos.SellerResponse;
import com.zondamarket.identity.repository.AccountAddressRepository;
import com.zondamarket.identity.repository.SellerProfileRepository;
import com.zondamarket.identity.repository.UserAccountRepository;
import com.zondamarket.review.service.ReviewService;
import com.zondamarket.shared.errors.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final UserAccountRepository accounts;
    private final AccountAddressRepository addresses;
    private final SellerProfileRepository sellers;
    private final ReviewService reviews;

    ProfileService(UserAccountRepository accounts, AccountAddressRepository addresses, SellerProfileRepository sellers,
                   ReviewService reviews) {
        this.accounts = accounts;
        this.addresses = addresses;
        this.sellers = sellers;
        this.reviews = reviews;
    }

    @Transactional
    public AccountResponse update(UUID accountId, ProfileUpdate update) {
        UserAccount account = lockAccount(accountId);
        account.rename(update.displayName());
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> addresses(UUID accountId) {
        return addresses.findByAccountIdAndDeletedFalseOrderByCreatedAtAscIdAsc(accountId).stream()
                .map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse addAddress(UUID accountId, AddressRequest address) {
        return AddressResponse.from(addresses.save(new AccountAddress(accountId, address.street(),
                address.city(), address.provinceCode(), address.postalCode())));
    }

    @Transactional
    public AddressResponse updateAddress(UUID accountId, UUID id, AddressPatch update) {
        AccountAddress address = lockAddress(accountId, id);
        address.update(orCurrent(update.street(), address.getStreet()), orCurrent(update.city(), address.getCity()),
                orCurrent(update.provinceCode(), address.getProvinceCode()),
                orCurrent(update.postalCode(), address.getPostalCode()));
        return AddressResponse.from(address);
    }

    @Transactional
    public void removeAddress(UUID accountId, UUID id) {
        lockAddress(accountId, id).archive();
    }

    @Transactional
    public SellerResponse activate(UUID accountId, SellerActivationRequest request, UUID traceId) {
        UserAccount account = lockAccount(accountId);
        if (account.getRole() == Role.SELLER) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "SELLER_ALREADY_ACTIVE", "Ya tenés un perfil de vendedor.");
        }
        if (account.getRole() != Role.USER) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "SELLER_ACTIVATION_FORBIDDEN", "Esta cuenta no puede activar un perfil.");
        }
        if (!account.isEmailVerified()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verificá tu correo para empezar a vender.");
        }

        try {
            var profile = sellers.saveAndFlush(new SellerProfile(account, request.type(), request.displayName(),
                    request.slug(), request.bio(), traceId));
            account.becomeSeller();
            return SellerResponse.from(profile);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "uq_seller_profile_slug".equals(violation.getConstraintName())) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "SELLER_SLUG_IN_USE", "Ese identificador ya está en uso.");
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public SellerResponse ownSeller(UUID accountId) {
        return sellers.findByAccountIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(accountId, Role.SELLER)
                .map(this::withReputation)
                .orElseThrow(ProfileService::missingSeller);
    }

    @Transactional(readOnly = true)
    public SellerResponse publicSeller(String slug) {
        if (slug.length() > 50) {
            throw missingSeller();
        }
        return sellers.findBySlugAndDeletedFalseAndAccountDeletedFalseAndAccountSuspendedFalseAndAccountRole(slug, Role.SELLER)
                .map(this::withReputation).orElseThrow(ProfileService::missingSeller);
    }

    private SellerResponse withReputation(SellerProfile seller) {
        return SellerResponse.from(seller, reviews.reputation(seller.getId()));
    }

    private UserAccount lockAccount(UUID accountId) {
        return accounts.lockByIdAndDeletedFalse(accountId).filter(account -> !account.isSuspended())
                .orElseThrow(AuthService::unauthorized);
    }

    private AccountAddress lockAddress(UUID accountId, UUID id) {
        return addresses.lockOwned(id, accountId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "No encontramos esa dirección."));
    }

    private static String orCurrent(String update, String current) {
        return update == null ? current : update;
    }

    private static ApiProblemException missingSeller() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "No encontramos ese perfil de vendedor.");
    }
}
