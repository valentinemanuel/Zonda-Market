package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentRefundGateway {
    Method refundMethod();

    Observation inspect(Charge charge) throws PaymentProviderReadFailure;

    Observation refund(Charge charge) throws PaymentProviderReadFailure;

    record Charge(UUID key, String orderId, String paymentId, String reference, BigDecimal amount, String currency) {}

    enum State { AVAILABLE, PENDING, CONFIRMED, REVIEW }

    record Observation(State state, String refundId) {}
}
