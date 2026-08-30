package com.example.platform.payments.exception;

import java.util.UUID;

/** No payment transaction with the given id. Maps to 404 Not Found. */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID id) {
        super("No payment transaction found with id " + id);
    }
}
