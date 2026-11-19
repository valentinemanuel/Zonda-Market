package com.zondamarket.identity.dto;

import com.zondamarket.identity.domain.UserAccount;
import com.zondamarket.identity.domain.UserAccount.Role;
import java.util.List;
import java.util.UUID;

public record AccountResponse(UUID id, String email, boolean emailVerified, List<Role> roles, String displayName) {
    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(account.getId(), account.getEmail(), account.isEmailVerified(),
                List.of(account.getRole()), account.getDisplayName());
    }
}
