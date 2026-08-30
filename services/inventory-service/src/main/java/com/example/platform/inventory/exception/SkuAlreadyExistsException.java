package com.example.platform.inventory.exception;

/** A product with this SKU already exists. Maps to 409 Conflict. */
public class SkuAlreadyExistsException extends RuntimeException {
    public SkuAlreadyExistsException(String sku) {
        super("A product already exists with sku " + sku);
    }
}
