package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile(LocalPaymentPort.PROFILES)
class LocalPaymentStrategy implements PaymentStrategy {
    @Override
    public Set<Method> methods() {
        return Set.of(Method.CARD, Method.CHECKOUT_PRO);
    }

    @Override
    public void submit(PaymentCommand command) throws SubmissionUncertain {
        throw new SubmissionUncertain();
    }
}
