package com.zondamarket.catalog.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "listing_image", schema = "zonda_market")
public class ListingImage extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private UUID listingId;

    @Column(nullable = false, updatable = false, length = 20)
    private String provider;

    @Column(nullable = false, updatable = false)
    private UUID publicId;

    @Column(nullable = false, updatable = false, length = 400)
    private String url;

    @Column(nullable = false, updatable = false, length = 400)
    private String thumbnailUrl;

    @Column(nullable = false, length = 160)
    private String alt;

    @Column(nullable = false)
    private int position;

    protected ListingImage() {}

    public ListingImage(UUID listingId, String provider, UUID publicId, String url, String thumbnailUrl,
                        String alt, int position) {
        this.listingId = listingId;
        this.provider = provider;
        this.publicId = publicId;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.alt = alt;
        this.position = position;
    }

    public void move(int position) { this.position = position; }
    public void changeAlt(String alt) { this.alt = alt; }
    public void archive() { markDeleted(); }

    public String getProvider() { return provider; }
    public UUID getPublicId() { return publicId; }
    public String getUrl() { return url; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getAlt() { return alt; }
    public int getPosition() { return position; }
}
