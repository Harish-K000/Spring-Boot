package com.example.platform.worker.consumer;

import com.example.platform.worker.stream.EventStream;
import com.example.platform.worker.stream.StreamMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final EventStream eventStream;
    private final EventProcessingService processingService;
    private final int batchSize;
    private final int maxDeliveries;
    private final MeterRegistry metrics;

    public DomainEventConsumer(
            EventStream eventStream,
            EventProcessingService processingService,
            @Value("${worker.stream.batch-size:25}") int batchSize,
            @Value("${worker.stream.max-deliveries:5}") int maxDeliveries,
            MeterRegistry metrics) {
        this.eventStream = eventStream;
        this.processingService = processingService;
        this.batchSize = batchSize;
        this.maxDeliveries = maxDeliveries;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${worker.stream.fixed-delay-ms:500}",
            initialDelayString = "${worker.stream.initial-delay-ms:2500}")
    @Observed(name = "platform.worker.event-consumer", contextualName = "consume-domain-events")
    public void consume() {
        for (StreamMessage message : eventStream.read(batchSize)) {
            try {
                boolean processed = processingService.process(message.event());
                eventStream.acknowledge(message.recordId());
                count(processed ? "processed" : "duplicate", message);
            } catch (RuntimeException ex) {
                handleFailure(message, ex);
            }
        }
    }

    private void handleFailure(StreamMessage message, RuntimeException failure) {
        if (message.deliveryCount() >= maxDeliveries) {
            try {
                eventStream.deadLetter(message, failure);
                eventStream.acknowledge(message.recordId());
                count("dead_lettered", message);
                log.error("Dead-lettered Redis event {} after {} deliveries",
                        message.event().id(), message.deliveryCount(), failure);
                return;
            } catch (RuntimeException deadLetterFailure) {
                failure.addSuppressed(deadLetterFailure);
            }
        }
        count("failed", message);
        // Leave it pending; Redis will reclaim it after the configured idle period.
        log.warn("Could not process Redis event {} on delivery {}",
                message.event().id(), message.deliveryCount(), failure);
    }

    private void count(String outcome, StreamMessage message) {
        metrics.counter("platform.events.consumed",
                "outcome", outcome,
                "source", message.event().source()).increment();
    }
}
