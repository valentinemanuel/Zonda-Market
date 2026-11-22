package com.zondamarket.identity.repository;

import com.zondamarket.identity.domain.SellerProfile;
import com.zondamarket.identity.domain.UserAccount.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, UUID> {
    Optional<SellerProfile> findByAccountIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(UUID accountId, Role role);

    Optional<SellerProfile> findByIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(UUID id, Role role);

    Optional<SellerProfile> findBySlugAndDeletedFalseAndAccountDeletedFalseAndAccountSuspendedFalseAndAccountRole(
            String slug, Role role);
}
