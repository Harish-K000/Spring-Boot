package com.example.platform.inventory.dto;

import jakarta.validation.constraints.Min;

public record StockChangeRequest(

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity
) {}
