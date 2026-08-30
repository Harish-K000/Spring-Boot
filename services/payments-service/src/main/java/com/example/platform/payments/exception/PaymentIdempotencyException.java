package com.example.platform.payments.exception;

public class PaymentIdempotencyException extends RuntimeException {
    public PaymentIdempotencyException() {
        super("Idempotency-Key was already used for a different payment request");
    }
}
