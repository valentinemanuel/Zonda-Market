package com.zondamarket.checkout.dto;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import java.util.Locale;
import java.util.regex.Pattern;

public enum PaymentWebhookTopic {
    PAYMENT(Method.CHECKOUT_PRO, "[0-9]{1,30}"),
    ORDER(Method.CARD, "(?i)ORD[A-Z0-9]{16,77}");

    private final Method method;
    private final Pattern resourceFormat;

    PaymentWebhookTopic(Method method, String resourceFormat) {
        this.method = method;
        this.resourceFormat = Pattern.compile(resourceFormat);
    }

    public Method method() { return method; }

    public boolean accepts(String resource) {
        return resource != null && resourceFormat.matcher(resource).matches();
    }

    public String webhookName() { return name().toLowerCase(Locale.ROOT); }
}
