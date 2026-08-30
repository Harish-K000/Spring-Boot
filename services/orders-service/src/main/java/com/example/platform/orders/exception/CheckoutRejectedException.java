package com.example.platform.orders.exception;

/** The checkout cannot proceed because its catalog or inventory request is invalid. */
public class CheckoutRejectedException extends RuntimeException {

    public CheckoutRejectedException(String message) {
        super(message);
    }

    public CheckoutRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
