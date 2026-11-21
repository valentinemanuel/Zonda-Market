package com.zondamarket.catalog.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@DynamicUpdate
@Table(name = "listing_sku", schema = "zonda_market")
public class ListingSku extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private UUID listingId;

    @Column(nullable = false, updatable = false, length = 64)
    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> attributes;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int availableStock;

    @Column(nullable = false)
    private int reservedStock;

    @Version
    private long version;

    protected ListingSku() {}

    public ListingSku(UUID listingId, String code, Map<String, String> attributes, BigDecimal price, int initialStock) {
        this.listingId = listingId;
        this.code = code;
        this.availableStock = initialStock;
        edit(attributes, price);
    }

    public void edit(Map<String, String> attributes, BigDecimal price) {
        this.attributes = attributes;
        this.price = price.setScale(2);
    }

    public void changeAvailableStock(int availableStock) {
        this.availableStock = availableStock;
    }

    public UUID getListingId() { return listingId; }
    public String getCode() { return code; }
    public Map<String, String> getAttributes() { return attributes; }
    public BigDecimal getPrice() { return price; }
    public int getAvailableStock() { return availableStock; }
    public int getReservedStock() { return reservedStock; }
    public long getVersion() { return version; }
}
