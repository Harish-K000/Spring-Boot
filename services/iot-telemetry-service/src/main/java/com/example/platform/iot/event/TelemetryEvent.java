package com.example.platform.iot.event;

import com.example.platform.iot.dto.TelemetryRequest;
import java.time.Instant;
import java.util.UUID;

public record TelemetryEvent(
        UUID eventId, String tenantId, String deviceId, Instant recordedAt,
        Double temperatureC, Double pressureBar, Double vibrationMmS,
        Double rpm, Double voltage, Double currentAmps, Double runtimeHours,
        String errorCode
) {
    public static TelemetryEvent from(TelemetryRequest r) {
        return new TelemetryEvent(r.eventId(), r.tenantId(), r.deviceId(), r.recordedAt(),
                r.temperatureC(), r.pressureBar(), r.vibrationMmS(), r.rpm(), r.voltage(),
                r.currentAmps(), r.runtimeHours(), r.errorCode());
    }
}
