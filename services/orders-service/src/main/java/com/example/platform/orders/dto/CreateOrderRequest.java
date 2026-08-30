package com.example.platform.orders.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderRequest(

        @NotNull(message = "userId is required")
        UUID userId,

        @NotNull(message = "totalAmount is required")
        @DecimalMin(value = "0.00", message = "totalAmount must not be negative")
        @Digits(integer = 10, fraction = 2, message = "totalAmount must have at most 10 integer and 2 fraction digits")
        BigDecimal totalAmount,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO-4217 code")
        String currency
) {}
