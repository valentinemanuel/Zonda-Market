package com.zondamarket.identity.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_account", schema = "zonda_market")
public class UserAccount extends BaseEntity {
    public enum Role { USER, SELLER, ADMIN }

    @Column(nullable = false, length = 254)
    private String email;

    @Column(length = 80)
    private String displayName;

    @Column(length = 60)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean emailVerified;

    protected UserAccount() {}

    public UserAccount(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = Role.USER;
    }

    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void becomeSeller() {
        role = Role.SELLER;
    }

    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isEmailVerified() { return emailVerified; }

    public void verifyEmail() {
        emailVerified = true;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
