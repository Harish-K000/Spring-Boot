package com.example.platform.payments.dto;

import com.example.platform.payments.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        String provider,
        PaymentStatus status,
        String providerReference,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) {
    public PaymentResponse(UUID id, UUID orderId, BigDecimal amount, String currency,
                           String provider, PaymentStatus status, Instant createdAt, Instant updatedAt) {
        this(id, orderId, amount, currency, provider, status, null, null, createdAt, updatedAt);
    }
}
