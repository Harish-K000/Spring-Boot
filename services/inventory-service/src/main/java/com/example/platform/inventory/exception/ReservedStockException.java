package com.example.platform.inventory.exception;

/** Prevents deletion of a product whose stock is still committed to pending orders. */
public class ReservedStockException extends RuntimeException {

    public ReservedStockException(String sku, int reserved) {
        super("Cannot delete " + sku + ": " + reserved + " units are reserved");
    }
}
