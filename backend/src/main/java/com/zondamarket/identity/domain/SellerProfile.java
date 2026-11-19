package com.zondamarket.identity.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "seller_profile", schema = "zonda_market")
public class SellerProfile extends BaseEntity {
    public enum Type { PERSONAL, COMERCIO }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private UserAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Type type;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, updatable = false, length = 50)
    private String slug;

    @Column(nullable = false, length = 500)
    private String bio;

    @Column(nullable = false, updatable = false)
    private UUID activationTraceId;

    protected SellerProfile() {}

    public SellerProfile(UserAccount account, Type type, String displayName, String slug, String bio, UUID traceId) {
        this.account = account;
        this.type = type;
        this.displayName = displayName;
        this.slug = slug;
        this.bio = bio;
        this.activationTraceId = traceId;
    }

    public Type getType() { return type; }
    public String getDisplayName() { return displayName; }
    public String getSlug() { return slug; }
    public String getBio() { return bio; }
}
