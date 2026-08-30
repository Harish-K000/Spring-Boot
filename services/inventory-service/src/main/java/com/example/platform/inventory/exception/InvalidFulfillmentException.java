package com.example.platform.inventory.exception;

/** Attempted to fulfill more units than the product currently has reserved. */
public class InvalidFulfillmentException extends RuntimeException {

    public InvalidFulfillmentException(String sku, int requested, int reserved) {
        super("Cannot fulfill " + requested + " of " + sku + ": only " + reserved + " reserved");
    }
}
