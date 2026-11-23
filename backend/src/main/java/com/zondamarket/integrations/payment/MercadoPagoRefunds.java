package com.zondamarket.integrations.payment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mercadopago.exceptions.MPApiException;
import com.zondamarket.checkout.service.PaymentRefundGateway.Charge;
import com.zondamarket.checkout.service.PaymentRefundGateway.Observation;
import com.zondamarket.checkout.service.PaymentRefundGateway.PartialCharge;
import com.zondamarket.checkout.service.PaymentRefundGateway.State;
import java.util.Set;

final class MercadoPagoRefunds {
    static final Observation REVIEW = new Observation(State.REVIEW, null);
    static final Observation REJECTED = new Observation(State.REJECTED, null);
    private static final Set<String> PENDING = Set.of("pending", "processing", "in_process", "action_required", "in_review");
    private static final Set<String> WRITE_REJECTION = Set.of(
            "refund_amount_exceeds", "amount_not_refundable", "insufficient_money_for_refund", "2024", "3024", "4295", "15016");

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

    static Observation partial(JsonArray history, PartialCharge request, String paymentField,
                               String approved, boolean available, boolean responseToWrite) {
        JsonObject candidate = null;
        if (history != null) for (var entry : history) {
            if (!entry.isJsonObject()) return REVIEW;
            var row = entry.getAsJsonObject();
            String id = value(row, "id");
            if (!id.matches("[A-Za-z0-9_-]{1,80}")) return REVIEW;
            if (request.knownIds().contains(id)) continue;
            if (candidate != null) return REVIEW;
            candidate = row;
        }
        if (candidate == null) {
            return new Observation(!responseToWrite && request.refundId() == null && available
                    ? State.AVAILABLE : State.PENDING, null);
        }
        var charge = request.refund();
        var id = value(candidate, "id");
        var amount = candidate.get("amount");
        if (!charge.paymentId().equals(value(candidate, paymentField)) || amount == null || !amount.isJsonPrimitive()
                || charge.amount().compareTo(amount.getAsBigDecimal()) != 0) return REVIEW;
        if (request.refundId() != null && !request.refundId().equals(id)) return REVIEW;
        if (request.refundId() == null && !responseToWrite) {
            // Only the reply to a write with our key can bind an unknown provider ID.
            return request.sent() ? new Observation(State.AVAILABLE, null) : REVIEW;
        }
        var status = value(candidate, "status");
        if (approved.equals(status)) return new Observation(State.CONFIRMED, id);
        if (PENDING.contains(status)) return new Observation(State.PENDING, id);
        if (Set.of("rejected", "failed", "cancelled", "canceled").contains(status)) {
            return new Observation(State.REJECTED, id);
        }
        return REVIEW;
    }

    static boolean definitiveWriteRejection(MPApiException error, PartialCharge request) {
        if (request.sent() || error.getStatusCode() < 400 || error.getStatusCode() >= 500 || error.getApiResponse() == null) {
            return false;
        }
        try {
            var reply = JsonParser.parseString(error.getApiResponse().getContent()).getAsJsonObject();
            if (rejectionCode(reply)) return true;
            for (String key : Set.of("errors", "cause")) {
                var details = reply.get(key);
                if (details != null && details.isJsonArray() && details.getAsJsonArray().size() == 1) {
                    var first = details.getAsJsonArray().get(0);
                    if (first.isJsonObject() && rejectionCode(first.getAsJsonObject())) return true;
                }
            }
        } catch (RuntimeException malformed) {
            return false;
        }
        return false;
    }

    private static boolean rejectionCode(JsonObject item) {
        return WRITE_REJECTION.contains(value(item, "code")) || WRITE_REJECTION.contains(value(item, "error"));
    }

    private static String value(JsonObject json, String name) {
        var field = json.get(name);
        return field != null && field.isJsonPrimitive() ? field.getAsString() : "";
    }
}
