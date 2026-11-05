package com.example.platform.iot.service;

import com.example.platform.iot.event.TelemetryEvent;
import com.example.platform.iot.repository.AlertRepository;
import com.example.platform.iot.repository.TelemetryReadingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelemetryConsumerTest {

    @Test
    void duplicateEventIsIgnoredBeforeCreatingAnotherAlert() {
        var telemetryRepository = mock(TelemetryReadingRepository.class);
        var alertRepository = mock(AlertRepository.class);
        when(telemetryRepository.existsByEventId(any(UUID.class))).thenReturn(true);

        var consumer = new TelemetryConsumer(
                telemetryRepository, alertRepository, new ThresholdEvaluator(),
                new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"), ZoneOffset.UTC));

        var event = new TelemetryEvent(
                UUID.randomUUID(), "tenant-a", "pump-17", Instant.parse("2026-11-05T14:59:00Z"),
                95.0, null, 15.0, null, null, null, null, null);

        consumer.consume(event);

        verify(telemetryRepository, never()).save(any());
        verifyNoInteractions(alertRepository);
    }

    @Test
    void persistsReadingAndCreatesThresholdAlerts() {
        var telemetryRepository = mock(TelemetryReadingRepository.class);
        var alertRepository = mock(AlertRepository.class);
        when(telemetryRepository.existsByEventId(any(UUID.class))).thenReturn(false);

        var consumer = new TelemetryConsumer(
                telemetryRepository, alertRepository, new ThresholdEvaluator(),
                new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"), ZoneOffset.UTC));

        var event = new TelemetryEvent(
                UUID.randomUUID(), "tenant-a", "pump-17", Instant.parse("2026-11-05T14:59:00Z"),
                95.0, null, 15.0, null, null, null, null, null);

        consumer.consume(event);

        verify(telemetryRepository).save(any());
        verify(alertRepository, times(2)).save(any());
    }
}
