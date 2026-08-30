package com.example.platform.worker.event;

import java.time.Instant;
import java.util.UUID;

public record DomainEvent(
        UUID id,
        String source,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant occurredAt) {
}
