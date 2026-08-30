package com.example.platform.payments.exception;

import java.util.UUID;

/** The order already has a payment that is in flight or settled. Maps to 409 Conflict. */
public class DuplicatePaymentException extends RuntimeException {
    public DuplicatePaymentException(UUID orderId) {
        super("Order " + orderId + " already has an active payment");
    }
}
