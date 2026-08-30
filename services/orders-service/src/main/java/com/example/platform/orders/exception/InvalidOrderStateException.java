package com.example.platform.orders.exception;

import com.example.platform.orders.model.OrderStatus;

/** Requested status transition is not allowed from the order's current state. Maps to 409 Conflict. */
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(OrderStatus from, OrderStatus to) {
        super("Cannot transition an order from " + from + " to " + to);
    }
}
