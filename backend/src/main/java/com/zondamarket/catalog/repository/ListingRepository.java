package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.Listing;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ListingRepository extends JpaRepository<Listing, UUID> {
    List<Listing> findAllBySellerIdAndDeletedFalseOrderByCreatedAtDescIdAsc(UUID sellerId);

    Optional<Listing> findByIdAndSellerIdAndDeletedFalse(UUID id, UUID sellerId);

    // Serializes category changes with variant commands so both use a consistent schema.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Listing> findLockedByIdAndSellerIdAndDeletedFalse(UUID id, UUID sellerId);
}
