package com.zondamarket.checkout.service;

import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.shared.errors.ApiProblemException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile(LocalPaymentPort.PROFILES)
class LocalPaymentAdapter implements LocalPaymentPort {
    private final CheckoutStore orders;

    LocalPaymentAdapter(CheckoutStore orders) {
        this.orders = orders;
    }

    @Override
    public VerifiedOutcome verify(UUID buyer, UUID orderId, String reference, Outcome outcome) {
        var order = orders.find(buyer, orderId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No encontramos esa orden."));
        if (!LocalPaymentPort.reference(orderId).equals(reference)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "PAYMENT_REFERENCE_MISMATCH",
                    "La referencia de prueba no corresponde a la orden.");
        }
        return new VerifiedOutcome(reference, outcome, order.total(), order.currency());
    }
}
