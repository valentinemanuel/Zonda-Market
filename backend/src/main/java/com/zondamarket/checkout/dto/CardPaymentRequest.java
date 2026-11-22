package com.zondamarket.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CardPaymentRequest(
        @NotNull UUID attemptId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{16,256}") String token,
        @NotNull @Pattern(regexp = "[a-z0-9_]{1,40}") String paymentMethodId,
        @NotNull @Pattern(regexp = "credit_card|debit_card") String paymentType,
        @NotNull @Min(1) @Max(48) Integer installments,
        @NotNull @Valid Payer payer) {

    public record Payer(@NotBlank @Email @Size(max = 254) String email, @Valid Identification identification) {
        @Override
        public String toString() { return "Payer[redacted]"; }
    }

    public record Identification(@NotNull @Pattern(regexp = "[A-Za-z0-9]{1,12}") String type,
                                 @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,32}") String number) {
        @Override
        public String toString() { return "Identification[redacted]"; }
    }

    @Override
    public String toString() { return "CardPaymentRequest[attemptId=" + attemptId + "]"; }
}
