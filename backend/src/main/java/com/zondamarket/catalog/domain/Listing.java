package com.zondamarket.catalog.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "listing", schema = "zonda_market")
public class Listing extends BaseEntity {
    public enum Status { BORRADOR }

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

    public UUID getCategoryId() { return categoryId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Map<String, Object> getSpecs() { return specs; }
    public Status getStatus() { return status; }
    public long getVersion() { return version; }
}
