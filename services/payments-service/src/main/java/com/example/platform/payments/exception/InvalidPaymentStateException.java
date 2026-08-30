package com.example.platform.payments.exception;

import com.example.platform.payments.model.PaymentStatus;

/** Requested transition is not allowed from the payment's current state. Maps to 409 Conflict. */
public class InvalidPaymentStateException extends RuntimeException {
    public InvalidPaymentStateException(PaymentStatus from, PaymentStatus to) {
        super("Cannot transition a payment from " + from + " to " + to);
    }
}
