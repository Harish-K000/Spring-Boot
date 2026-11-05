package com.example.platform.iot.service;

import com.example.platform.iot.event.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdEvaluatorTest {
    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();

    @Test
    void createsSignalsOnlyForExceededThresholds() {
        var event = new TelemetryEvent(
                UUID.randomUUID(), "tenant-a", "pump-17", Instant.parse("2026-11-05T15:00:00Z"),
                91.2, 10.0, 14.5, 1800.0, 480.0, 12.0, 3200.0, null);

        assertThat(evaluator.evaluate(event))
                .extracting(ThresholdEvaluator.AlertSignal::type)
                .containsExactlyInAnyOrder("HIGH_TEMPERATURE", "HIGH_VIBRATION");
    }

    @Test
    void deviceErrorIsCriticalEvenWhenSensorValuesAreNormal() {
        var event = new TelemetryEvent(
                UUID.randomUUID(), "tenant-a", "pump-17", Instant.parse("2026-11-05T15:00:00Z"),
                60.0, 10.0, 4.5, 1800.0, 480.0, 12.0, 3200.0, "E-STOP");

        assertThat(evaluator.evaluate(event))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.severity()).isEqualTo("CRITICAL");
                    assertThat(signal.type()).isEqualTo("DEVICE_ERROR");
                });
    }
}
