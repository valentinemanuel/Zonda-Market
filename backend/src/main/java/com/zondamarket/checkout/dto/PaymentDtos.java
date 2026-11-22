package com.zondamarket.checkout.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos() {}

    public enum Method { CARD, CHECKOUT_PRO }

    public enum Status {
        CREADO, ENVIADO, PENDIENTE, APROBADO, RECHAZADO, RESULTADO_DESCONOCIDO;

        public boolean active() {
            return this != APROBADO && this != RECHAZADO;
        }
    }

    public enum NextAction { CONSULTAR, ELEGIR_MEDIO, NINGUNA }

    public record StartRequest(@NotNull Method method) {}

    public record PaymentAttemptResponse(UUID id, Method method, Status status, NextAction nextAction,
                                         Instant expiresAt) {}

    public record PaymentOptions(List<Method> methods, PaymentAttemptResponse latestAttempt) {}
}
