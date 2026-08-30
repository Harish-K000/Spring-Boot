package com.example.platform.inventory.exception;

import java.util.UUID;

/** No product with the given id. Maps to 404 Not Found. */
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(UUID id) {
        super("No product found with id " + id);
    }

    public ProductNotFoundException(String sku) {
        super("No product found with sku " + sku);
    }
}
