package com.example.platform.orders.exception;

/** Inventory could not complete an operation required by checkout. */
public class CheckoutDependencyException extends RuntimeException {

    public CheckoutDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
