package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PaymentRefundGateway {
    Method refundMethod();

    Observation inspect(Charge charge) throws PaymentProviderReadFailure;

    Observation refund(Charge charge) throws PaymentProviderReadFailure;

    default Observation inspectPartial(PartialCharge charge) throws PaymentProviderReadFailure {
        return new Observation(State.REVIEW, null);
    }

    default Observation refundPartial(PartialCharge charge) throws PaymentProviderReadFailure {
        return new Observation(State.REVIEW, null);
    }

    record Charge(UUID key, String orderId, String paymentId, String reference, BigDecimal amount, String currency) {}

    record PartialCharge(Charge refund, BigDecimal captured, String refundId, List<String> knownIds, boolean sent) {}

    enum State { AVAILABLE, PENDING, CONFIRMED, REVIEW, REJECTED }

    record Observation(State state, String refundId) {}
}
