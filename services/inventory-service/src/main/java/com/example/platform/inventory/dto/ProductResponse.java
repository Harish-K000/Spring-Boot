package com.example.platform.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String sku,
        String name,
        int quantityOnHand,
        int reservedQuantity,
        int availableQuantity,
        BigDecimal unitPrice,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
    public ProductResponse(UUID id, String sku, String name, int quantityOnHand,
                           int reservedQuantity, int availableQuantity,
                           Instant createdAt, Instant updatedAt) {
        this(id, sku, name, quantityOnHand, reservedQuantity, availableQuantity,
                new BigDecimal("1.00"), "USD", createdAt, updatedAt);
    }
}
