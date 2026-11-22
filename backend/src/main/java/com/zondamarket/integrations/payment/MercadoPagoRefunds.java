package com.zondamarket.integrations.payment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zondamarket.checkout.service.PaymentRefundGateway.Charge;
import com.zondamarket.checkout.service.PaymentRefundGateway.Observation;
import com.zondamarket.checkout.service.PaymentRefundGateway.State;
import java.util.Set;

final class MercadoPagoRefunds {
    static final Observation REVIEW = new Observation(State.REVIEW, null);
    private static final Set<String> PENDING = Set.of("pending", "processing", "in_process", "action_required", "in_review");

    private MercadoPagoRefunds() {}

    static Observation read(JsonArray refunds, Charge charge, String paymentField, String approved, boolean available) {
        if (refunds == null || refunds.isEmpty()) {
            return new Observation(available ? State.AVAILABLE : State.PENDING, null);
        }
        // An existing partial or conflicting refund needs review before any further debit to the collector.
        if (refunds.size() != 1 || !refunds.get(0).isJsonObject()) return REVIEW;
        return read(refunds.get(0).getAsJsonObject(), charge, paymentField, approved);
    }

    static Observation read(JsonObject refund, Charge charge, String paymentField, String approved) {
        var id = value(refund, "id");
        var amount = refund.get("amount");
        if (!id.matches("[A-Za-z0-9_-]{1,80}") || !charge.paymentId().equals(value(refund, paymentField))
                || amount == null || !amount.isJsonPrimitive()
                || charge.amount().compareTo(amount.getAsBigDecimal()) != 0) return REVIEW;
        var status = value(refund, "status");
        if (approved.equals(status)) return new Observation(State.CONFIRMED, id);
        if (PENDING.contains(status)) return new Observation(State.PENDING, id);
        return new Observation(State.REVIEW, id);
    }

    private static String value(JsonObject json, String name) {
        var field = json.get(name);
        return field != null && field.isJsonPrimitive() ? field.getAsString() : "";
    }
}
