package com.example.platform.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record TelemetryRequest(
        @NotNull UUID eventId,
        @NotBlank String tenantId,
        @NotBlank String deviceId,
        @NotNull Instant recordedAt,
        Double temperatureC,
        Double pressureBar,
        Double vibrationMmS,
        Double rpm,
        Double voltage,
        Double currentAmps,
        Double runtimeHours,
        String errorCode
) {
    public boolean hasMeasurement() {
        return temperatureC != null || pressureBar != null || vibrationMmS != null
                || rpm != null || voltage != null || currentAmps != null || runtimeHours != null
                || (errorCode != null && !errorCode.isBlank());
    }
}
