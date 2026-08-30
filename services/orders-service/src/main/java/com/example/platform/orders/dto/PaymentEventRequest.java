package com.example.platform.orders.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentEventRequest(
        @NotNull(message = "paymentId is required") UUID paymentId
) {}
