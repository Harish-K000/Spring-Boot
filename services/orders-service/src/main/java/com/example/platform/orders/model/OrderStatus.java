package com.example.platform.orders.model;

import java.util.Set;

/** Lifecycle of an order. Allowed transitions are declared here and enforced by the service. */
public enum OrderStatus {

    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> Set.of(PAID, CANCELLED).contains(target);
            case PAID -> Set.of(SHIPPED, CANCELLED).contains(target);
            case SHIPPED -> Set.of(DELIVERED).contains(target);
            case DELIVERED, CANCELLED -> false;
        };
    }
}
