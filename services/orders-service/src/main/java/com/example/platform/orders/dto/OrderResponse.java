package com.example.platform.orders.dto;

import com.example.platform.orders.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record OrderResponse(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    public OrderResponse(UUID id, UUID userId, OrderStatus status, BigDecimal totalAmount,
                         String currency, Instant createdAt, Instant updatedAt) {
        this(id, userId, status, totalAmount, currency, List.of(), createdAt, updatedAt);
    }
}
