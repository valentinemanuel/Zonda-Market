package com.zondamarket.checkout.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ProPaymentRequest(@NotNull UUID attemptId) {}
