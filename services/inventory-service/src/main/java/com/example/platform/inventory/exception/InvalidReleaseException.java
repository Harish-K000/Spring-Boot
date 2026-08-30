package com.example.platform.inventory.exception;

/** Attempted to release more stock than is currently reserved. Maps to 409 Conflict. */
public class InvalidReleaseException extends RuntimeException {
    public InvalidReleaseException(String sku, int requested, int reserved) {
        super("Cannot release " + requested + " of " + sku + ": only " + reserved + " reserved");
    }
}
