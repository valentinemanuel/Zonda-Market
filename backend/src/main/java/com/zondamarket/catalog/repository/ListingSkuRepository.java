package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.ListingSku;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ListingSkuRepository extends JpaRepository<ListingSku, UUID> {
    List<ListingSku> findAllByListingIdAndDeletedFalseOrderByCreatedAtAscIdAsc(UUID listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ListingSku> findByIdAndListingIdAndDeletedFalse(UUID id, UUID listingId);

    // Punto de bloqueo del SKU para ajustes de stock y futuras reservas de checkout.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ListingSku> findLockedByIdAndDeletedFalse(UUID id);

    @Query("""
            select count(s) > 0 from ListingSku s, Listing l
            where s.id = :id and s.deleted = false and l.id = s.listingId
              and l.sellerId = :seller and l.deleted = false
            """)
    boolean existsOwnedSku(UUID id, UUID seller);
}
