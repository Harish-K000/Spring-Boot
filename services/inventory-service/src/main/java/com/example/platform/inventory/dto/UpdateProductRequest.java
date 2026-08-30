package com.example.platform.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Adjusts the descriptive fields and the physical count. Reserved quantity is not settable here;
 * it only moves through the reserve and release operations.
 */
public record UpdateProductRequest(

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Min(value = 0, message = "quantityOnHand must not be negative")
        int quantityOnHand,

        @DecimalMin(value = "0.01", message = "unitPrice must be greater than zero")
        @Digits(integer = 10, fraction = 2, message = "unitPrice must have at most 10 integer and 2 fraction digits")
        BigDecimal unitPrice,

        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO-4217 code")
        String currency
) {
    public UpdateProductRequest(String name, int quantityOnHand) {
        this(name, quantityOnHand, null, null);
    }
}
