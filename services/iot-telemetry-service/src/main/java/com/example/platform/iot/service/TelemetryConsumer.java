package com.example.platform.iot.service;

import com.example.platform.iot.event.TelemetryEvent;
import com.example.platform.iot.model.Alert;
import com.example.platform.iot.model.TelemetryReading;
import com.example.platform.iot.repository.AlertRepository;
import com.example.platform.iot.repository.TelemetryReadingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class TelemetryConsumer {
    private final TelemetryReadingRepository telemetryRepository;
    private final AlertRepository alertRepository;
    private final ThresholdEvaluator thresholdEvaluator;
    private final Clock clock;
    private final Counter persisted;
    private final Counter duplicates;
    private final Counter alertsCreated;

    public TelemetryConsumer(
            TelemetryReadingRepository telemetryRepository,
            AlertRepository alertRepository,
            ThresholdEvaluator thresholdEvaluator,
            MeterRegistry meterRegistry) {
        this(telemetryRepository, alertRepository, thresholdEvaluator, meterRegistry, Clock.systemUTC());
    }

    TelemetryConsumer(
            TelemetryReadingRepository telemetryRepository,
            AlertRepository alertRepository,
            ThresholdEvaluator thresholdEvaluator,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.telemetryRepository = telemetryRepository;
        this.alertRepository = alertRepository;
        this.thresholdEvaluator = thresholdEvaluator;
        this.clock = clock;
        this.persisted = meterRegistry.counter("iot.telemetry.persisted");
        this.duplicates = meterRegistry.counter("iot.telemetry.duplicates");
        this.alertsCreated = meterRegistry.counter("iot.alerts.created");
    }

    @KafkaListener(topics = "${iot.kafka.telemetry-topic:iot.telemetry.v1}")
    @Transactional
    public void consume(TelemetryEvent event) {
        if (telemetryRepository.existsByEventId(event.eventId())) {
            duplicates.increment();
            return;
        }

        try {
            telemetryRepository.save(TelemetryReading.from(event));

            for (var signal : thresholdEvaluator.evaluate(event)) {
                alertRepository.save(new Alert(
                        event.eventId(),
                        event.tenantId(),
                        event.deviceId(),
                        signal.severity(),
                        signal.type(),
                        signal.message(),
                        clock.instant()));
                alertsCreated.increment();
            }

            persisted.increment();
        } catch (DataIntegrityViolationException duplicateRace) {
            // Database uniqueness is the final idempotency boundary.
            duplicates.increment();
        }
    }
}
