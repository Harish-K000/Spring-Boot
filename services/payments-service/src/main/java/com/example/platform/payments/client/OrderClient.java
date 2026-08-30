package com.example.platform.payments.client;

import java.math.BigDecimal;
import java.util.UUID;

public interface OrderClient {

    OrderSnapshot findOrder(UUID orderId);

    void markPaid(UUID orderId, UUID paymentId);

    void markRefunded(UUID orderId, UUID paymentId);

    record OrderSnapshot(
            UUID id,
            UUID userId,
            String status,
            BigDecimal totalAmount,
            String currency) {
    }
}
