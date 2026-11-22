package com.zondamarket.checkout.shared;

import com.zondamarket.shared.errors.ApiProblemException;
import org.springframework.http.HttpStatus;

public final class PaymentErrors {
    private PaymentErrors() {}

    public static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiProblemException notFound(String code, String detail) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiProblemException attemptNotFound() {
        return notFound("PAYMENT_ATTEMPT_NOT_FOUND", "No encontramos ese intento de pago.");
    }

    public static ApiProblemException ineligible() {
        return conflict("PAYMENT_ORDER_INELIGIBLE", "La orden no tiene una reserva vigente para pagar.");
    }
}
