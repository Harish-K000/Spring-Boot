package com.example.platform.worker.outbox;

import com.example.platform.worker.stream.EventStream;
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
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore outboxStore;
    private final EventStream eventStream;
    private final int batchSize;
    private final MeterRegistry metrics;

    public OutboxRelay(
            OutboxStore outboxStore,
            EventStream eventStream,
            @Value("${worker.relay.batch-size:50}") int batchSize,
            MeterRegistry metrics) {
        this.outboxStore = outboxStore;
        this.eventStream = eventStream;
        this.batchSize = batchSize;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${worker.relay.fixed-delay-ms:1000}",
            initialDelayString = "${worker.relay.initial-delay-ms:2000}")
    @Observed(name = "platform.worker.outbox-relay", contextualName = "relay-outbox")
    public void relay() {
        for (ClaimedOutboxEvent event : outboxStore.claimBatch(batchSize)) {
            try {
                eventStream.publish(event.event());
                outboxStore.markPublished(event);
                count("published", event);
            } catch (RuntimeException ex) {
                log.warn("Could not relay outbox event {}", event.event().id(), ex);
                outboxStore.markFailed(event, ex);
                count("failed", event);
            }
        }
    }

    private void count(String outcome, ClaimedOutboxEvent event) {
        metrics.counter("platform.outbox.relay",
                "outcome", outcome,
                "source", event.source().eventSource()).increment();
    }
}
