package com.example.platform.iot.service;

import com.example.platform.iot.dto.TelemetryRequest;
import com.example.platform.iot.event.TelemetryEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelemetryIngestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, TelemetryEvent> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    void publishesValidatedEventUsingDeviceIdAsKafkaKey() {
        when(kafkaTemplate.send(anyString(), anyString(), any(TelemetryEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        var service = new TelemetryIngestionService(
                kafkaTemplate, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), "iot.telemetry.v1");

        UUID eventId = UUID.randomUUID();
        var request = new TelemetryRequest(
                eventId, "tenant-a", "pump-17", NOW,
                65.0, null, 5.2, 1800.0, 480.0, 12.0, 3200.0, null);

        assertThat(service.ingest(request)).isEqualTo(eventId);
        verify(kafkaTemplate).send(eq("iot.telemetry.v1"), eq("pump-17"),
                argThat(e -> e.eventId().equals(eventId)));
    }

    @Test
    void rejectsPayloadWithoutAnyTelemetryValue() {
        var service = new TelemetryIngestionService(
                kafkaTemplate, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), "iot.telemetry.v1");

        var request = new TelemetryRequest(
                UUID.randomUUID(), "tenant-a", "pump-17", NOW,
                null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.ingest(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("At least one telemetry measurement");
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void rejectsTimestampTooFarInFuture() {
        var service = new TelemetryIngestionService(
                kafkaTemplate, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), "iot.telemetry.v1");

        var request = new TelemetryRequest(
                UUID.randomUUID(), "tenant-a", "pump-17", NOW.plusSeconds(3600),
                65.0, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.ingest(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("5 minutes in the future");
        verifyNoInteractions(kafkaTemplate);
    }
}
