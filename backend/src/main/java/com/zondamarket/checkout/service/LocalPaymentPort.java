package com.zondamarket.checkout.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface LocalPaymentPort {
    String PROFILES = "(local | test) & !demo & !prod & !production";

    enum Outcome { APPROVED, REJECTED }

    record VerifiedOutcome(String reference, Outcome outcome, BigDecimal amount, String currency) {}

    VerifiedOutcome verify(UUID buyer, UUID order, String reference, Outcome outcome);

    static String reference(UUID order) {
        return "local-" + order;
    }
}
