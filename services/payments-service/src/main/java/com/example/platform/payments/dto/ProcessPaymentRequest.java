package com.example.platform.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProcessPaymentRequest(
        @NotNull(message = "orderId is required") UUID orderId,

        @NotBlank(message = "paymentMethodToken is required")
        @Size(max = 200, message = "paymentMethodToken must be at most 200 characters")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String paymentMethodToken
) {}
