package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.ListingImage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {
    List<ListingImage> findAllByListingIdAndDeletedFalseOrderByPositionAscIdAsc(UUID listingId);

    @Query("""
            select image from ListingImage image where image.deleted = false and image.position = 0 and image.listingId in (
                select l.id from Listing l where l.sellerId = :seller and l.deleted = false
            ) order by image.id
            """)
    List<ListingImage> findSellerCovers(UUID seller);

    long countByListingIdAndDeletedFalse(UUID listingId);
}
