package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.ListingImage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {
    List<ListingImage> findAllByListingIdAndDeletedFalseOrderByPositionAscIdAsc(UUID listingId);

    long countByListingIdAndDeletedFalse(UUID listingId);
}
