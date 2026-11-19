package com.zondamarket.identity.dto;

import com.zondamarket.identity.domain.UserAccount.Role;
import java.util.List;

public record AccessTokenResponse(String accessToken, long expiresIn, List<Role> roles) {}
