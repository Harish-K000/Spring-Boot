package com.example.platform.orders.client;

import java.math.BigDecimal;
import java.util.UUID;

public interface InventoryClient {

    ProductSnapshot findProduct(UUID productId);

    void reserve(UUID productId, int quantity);

    void release(UUID productId, int quantity);

    record ProductSnapshot(
            UUID id,
            String sku,
            String name,
            int availableQuantity,
            BigDecimal unitPrice,
            String currency) {
    }
}
