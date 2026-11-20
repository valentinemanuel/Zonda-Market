package com.zondamarket.catalog.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "listing", schema = "zonda_market")
public class Listing extends BaseEntity {
    public enum Status { BORRADOR, ACTIVA, ARCHIVADA }

    @Column(nullable = false, updatable = false)
    private UUID sellerId;

    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 5000)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> specs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.BORRADOR;

    @Column(length = 200, unique = true)
    private String slug;

    @Version
    private long version;

    protected Listing() {}

    public Listing(UUID sellerId, UUID categoryId, String title, String description, Map<String, Object> specs) {
        this.sellerId = sellerId;
        edit(categoryId, title, description, specs);
    }

    public void edit(UUID categoryId, String title, String description, Map<String, Object> specs) {
        this.categoryId = categoryId;
        this.title = title.strip();
        this.description = description.strip();
        this.specs = specs;
    }

    public void publish() {
        if (slug == null) {
            String label = Normalizer.normalize(title, Normalizer.Form.NFD).toLowerCase(Locale.ROOT)
                    .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (label.length() > 80) {
                label = label.substring(0, 80).replaceFirst("-$", "");
            }
            slug = (label.isEmpty() ? "publicacion" : label) + "-" + getId();
        }
        status = Status.ACTIVA;
    }

    public void archive() {
        status = Status.ARCHIVADA;
    }

    public String getSlug() { return slug; }
    public UUID getSellerId() { return sellerId; }
    public UUID getCategoryId() { return categoryId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Map<String, Object> getSpecs() { return specs; }
    public Status getStatus() { return status; }
    public long getVersion() { return version; }
}
