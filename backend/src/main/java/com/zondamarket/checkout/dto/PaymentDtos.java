package com.zondamarket.checkout.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos() {}

    public enum Method { CARD, CHECKOUT_PRO }
    public enum Provider { LOCAL, MERCADO_PAGO }

    public enum Status {
        CREADO, ENVIADO, PENDIENTE, APROBADO, RECHAZADO, RESULTADO_DESCONOCIDO;

        public boolean active() {
            return this != APROBADO && this != RECHAZADO;
        }
    }

    public enum NextAction { INGRESAR_TARJETA, CONSULTAR, ELEGIR_MEDIO, NINGUNA }

    public record StartRequest(@NotNull Method method) {}

    public record PaymentAttemptResponse(UUID id, Method method, Provider provider, Status status, NextAction nextAction,
                                         Instant expiresAt) {}

    public record PaymentOptions(List<Method> methods, PaymentAttemptResponse latestAttempt, CardConfig card) {}
    public record CardConfig(String publicKey, String amount, String payerEmail) {}
}
