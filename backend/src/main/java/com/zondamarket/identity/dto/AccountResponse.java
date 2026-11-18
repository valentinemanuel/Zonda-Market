package com.zondamarket.identity.dto;

import com.zondamarket.identity.domain.UserAccount.Role;
import java.util.List;
import java.util.UUID;

public record AccountResponse(UUID id, String email, boolean emailVerified, List<Role> roles) {}
