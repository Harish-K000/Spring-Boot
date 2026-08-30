package com.example.platform.orders.exception;

import java.util.UUID;

/** No order with the given id. Maps to 404 Not Found. */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID id) {
        super("No order found with id " + id);
    }
}
