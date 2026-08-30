package com.example.platform.inventory.exception;

/** A stock-changing service call supplied a non-positive quantity. */
public class InvalidStockQuantityException extends RuntimeException {

    public InvalidStockQuantityException(int quantity) {
        super("Stock change quantity must be at least 1; received " + quantity);
    }
}
