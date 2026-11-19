package com.zondamarket.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private boolean deleted;

    public UUID getId() {
        return id;
    }

    protected void markDeleted() {
        deleted = true;
    }

    @PrePersist
    protected void initializeTimestamps() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void updateTimestamp() {
        updatedAt = Instant.now();
    }
}
