package com.example.platform.orders.dto;

import com.example.platform.orders.model.OrderStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** {@code totalAmount} is optional; when null the stored amount is left unchanged. */
public record UpdateOrderRequest(

        @NotNull(message = "status is required")
        OrderStatus status,

        @DecimalMin(value = "0.00", message = "totalAmount must not be negative")
        @Digits(integer = 10, fraction = 2, message = "totalAmount must have at most 10 integer and 2 fraction digits")
        BigDecimal totalAmount
) {}
