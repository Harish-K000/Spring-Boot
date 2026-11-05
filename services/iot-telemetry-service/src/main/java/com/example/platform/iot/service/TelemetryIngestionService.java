package com.example.platform.iot.service;

import com.example.platform.iot.dto.TelemetryRequest;
import com.example.platform.iot.event.TelemetryEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class TelemetryIngestionService {
    private final KafkaTemplate<String, TelemetryEvent> kafkaTemplate;
    private final Clock clock;
    private final String topic;
    private final Counter accepted;
    private final Counter publishFailures;

    public TelemetryIngestionService(
            KafkaTemplate<String, TelemetryEvent> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${iot.kafka.telemetry-topic:iot.telemetry.v1}") String topic) {
        this(kafkaTemplate, meterRegistry, Clock.systemUTC(), topic);
    }

    TelemetryIngestionService(
            KafkaTemplate<String, TelemetryEvent> kafkaTemplate,
            MeterRegistry meterRegistry,
            Clock clock,
            String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.topic = topic;
        this.accepted = meterRegistry.counter("iot.telemetry.accepted");
        this.publishFailures = meterRegistry.counter("iot.telemetry.publish.failures");
    }

    public UUID ingest(TelemetryRequest request) {
        validateBusinessRules(request);
        TelemetryEvent event = TelemetryEvent.from(request);

        try {
            kafkaTemplate.send(topic, event.deviceId(), event).get(3, TimeUnit.SECONDS);
            accepted.increment();
            return event.eventId();
        } catch (Exception ex) {
            publishFailures.increment();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Telemetry broker is unavailable; retry the same eventId",
                    ex);
        }
    }

    private void validateBusinessRules(TelemetryRequest request) {
        if (!request.hasMeasurement()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "At least one telemetry measurement or errorCode is required");
        }

        Instant now = clock.instant();
        if (request.recordedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "recordedAt cannot be more than 5 minutes in the future");
        }
    }
}
