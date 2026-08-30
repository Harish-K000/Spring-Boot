package com.example.platform.orders.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used for a different checkout request");
    }
}
