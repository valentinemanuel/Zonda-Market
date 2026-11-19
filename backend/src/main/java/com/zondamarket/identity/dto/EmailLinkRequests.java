package com.zondamarket.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class EmailLinkRequests {
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";

    private EmailLinkRequests() {}

    public record Request(@NotBlank @Email @Size(max = 254) String email) {
        public Request {
            email = RegisterRequest.normalizeEmail(email);
        }
    }

    public record Verification(@NotBlank @Pattern(regexp = TOKEN_PATTERN) String token) {}

    public record Reset(@NotBlank @Pattern(regexp = TOKEN_PATTERN) String token,
                        @NotBlank @Size(min = 12, max = 72) String password) {}
}
