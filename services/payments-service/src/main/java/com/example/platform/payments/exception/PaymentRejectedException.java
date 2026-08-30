package com.example.platform.payments.exception;

public class PaymentRejectedException extends RuntimeException {
    public PaymentRejectedException(String message) { super(message); }
    public PaymentRejectedException(String message, Throwable cause) { super(message, cause); }
}
