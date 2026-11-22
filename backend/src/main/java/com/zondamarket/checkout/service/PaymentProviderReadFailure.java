package com.zondamarket.checkout.service;

public class PaymentProviderReadFailure extends Exception {
    private final int httpStatus;

    public PaymentProviderReadFailure(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public boolean retryable() {
        return httpStatus <= 0 || httpStatus == 404 || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }

    public String code() {
        return httpStatus > 0 ? "PROVIDER_HTTP_" + httpStatus : "PROVIDER_UNAVAILABLE";
    }
}
