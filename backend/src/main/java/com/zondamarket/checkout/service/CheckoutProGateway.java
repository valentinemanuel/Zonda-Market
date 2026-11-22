package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutProGateway {
    record Checkout(UUID orderId, UUID idempotencyKey, String reference, BigDecimal amount, String currency,
                    Instant expiresAt) {}

    record Preference(String id, String url) {}

    record Payment(String id, String reference, BigDecimal amount, String currency, Status status) {}

    Preference create(Checkout checkout) throws Uncertain, Unavailable;

    Optional<Preference> recover(Checkout checkout) throws Uncertain;

    Optional<Payment> lookup(Checkout checkout) throws Uncertain;

    Optional<Payment> lookupPayment(String paymentId) throws PaymentProviderReadFailure;

    class Uncertain extends Exception {}
    class Unavailable extends Exception {}
}
