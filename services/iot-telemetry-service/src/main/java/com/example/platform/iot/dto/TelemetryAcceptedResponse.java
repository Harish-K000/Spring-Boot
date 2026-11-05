package com.example.platform.iot.dto;

import java.util.UUID;

public record TelemetryAcceptedResponse(UUID eventId, String status) {
    public static TelemetryAcceptedResponse accepted(UUID eventId) {
        return new TelemetryAcceptedResponse(eventId, "ACCEPTED");
    }
}
