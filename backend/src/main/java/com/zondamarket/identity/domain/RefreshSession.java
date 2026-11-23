package com.zondamarket.identity.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refresh_session", schema = "zonda_market")
public class RefreshSession extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private UserAccount account;

    @Column(nullable = false, length = 64)
    private String jtiHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private boolean rememberBrowser;

    @Column(nullable = false)
    private long generation;

    @Column(nullable = false)
    private long sessionVersion;

    private Instant revokedAt;

    protected RefreshSession() {}

    public RefreshSession(UserAccount account, String jtiHash, Instant expiresAt, boolean rememberBrowser) {
        this.account = account;
        this.jtiHash = jtiHash;
        this.expiresAt = expiresAt;
        this.rememberBrowser = rememberBrowser;
    }

    public UserAccount getAccount() { return account; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRememberBrowser() { return rememberBrowser; }
    public long getGeneration() { return generation; }
    public long getSessionVersion() { return sessionVersion; }

    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean matches(String hash, long tokenGeneration) {
        return generation == tokenGeneration && jtiHash.equals(hash);
    }

    public void rotate(String hash) {
        jtiHash = hash;
        generation++;
    }

    public void revoke(Instant now) {
        revokedAt = now;
        sessionVersion++;
    }
}
