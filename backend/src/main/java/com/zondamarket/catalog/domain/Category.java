package com.zondamarket.catalog.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "category", schema = "zonda_market")
public class Category extends BaseEntity {
    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120, unique = true)
    private String slug;

    private UUID parentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, List<String>> attributeSchema;

    public Map<String, List<String>> getAttributeSchema() {
        return attributeSchema;
    }

    protected Category() {
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public UUID getParentId() {
        return parentId;
    }
}
