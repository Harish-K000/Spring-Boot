package com.example.platform.payments.model;

import java.util.Set;

/** Lifecycle of a payment transaction. Allowed transitions are enforced by the service. */
public enum PaymentStatus {

    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case PENDING -> Set.of(AUTHORIZED, FAILED).contains(target);
            case AUTHORIZED -> Set.of(CAPTURED, FAILED).contains(target);
            case CAPTURED -> Set.of(REFUNDED).contains(target);
            case FAILED, REFUNDED -> false;
        };
    }
}
