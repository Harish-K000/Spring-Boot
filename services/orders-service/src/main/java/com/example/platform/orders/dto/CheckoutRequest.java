package com.example.platform.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CheckoutRequest(
        @NotEmpty(message = "items must contain at least one product")
        @Size(max = 50, message = "items must contain at most 50 products")
        List<@NotNull(message = "items must not contain null entries") @Valid CheckoutItemRequest> items
) {}
