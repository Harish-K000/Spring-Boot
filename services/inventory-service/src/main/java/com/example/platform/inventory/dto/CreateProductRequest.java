package com.example.platform.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateProductRequest(

        @NotBlank(message = "sku is required")
        @Size(max = 100, message = "sku must be at most 100 characters")
        String sku,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Min(value = 0, message = "quantityOnHand must not be negative")
        int quantityOnHand,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.01", message = "unitPrice must be greater than zero")
        @Digits(integer = 10, fraction = 2, message = "unitPrice must have at most 10 integer and 2 fraction digits")
        BigDecimal unitPrice,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO-4217 code")
        String currency
) {
    /** Convenience constructor retained for service-level callers created before catalog pricing. */
    public CreateProductRequest(String sku, String name, int quantityOnHand) {
        this(sku, name, quantityOnHand, new BigDecimal("1.00"), "USD");
    }
}
