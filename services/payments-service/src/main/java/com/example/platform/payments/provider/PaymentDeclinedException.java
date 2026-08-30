package com.example.platform.payments.provider;

public class PaymentDeclinedException extends RuntimeException {

    private final String code;

    public PaymentDeclinedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
