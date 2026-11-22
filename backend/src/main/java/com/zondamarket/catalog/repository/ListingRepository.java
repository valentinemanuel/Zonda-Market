package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.Listing;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ListingRepository extends JpaRepository<Listing, UUID> {
    List<Listing> findAllBySellerIdAndDeletedFalseOrderByCreatedAtDescIdAsc(UUID sellerId);

    Optional<Listing> findByIdAndSellerIdAndDeletedFalse(UUID id, UUID sellerId);

    @Query(value = """
            SELECT l.* FROM zonda_market.listing l
            JOIN zonda_market.public_listing visible ON visible.id = l.id WHERE visible.slug = :slug
            """, nativeQuery = true)
    Optional<Listing> findVisibleBySlug(String slug);

    // Serializes category changes with variant commands so both use a consistent schema.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Listing> findLockedByIdAndSellerIdAndDeletedFalse(UUID id, UUID sellerId);
}
