package com.example.platform.worker.outbox;

import com.example.platform.worker.event.DomainEvent;

import java.util.UUID;

public record ClaimedOutboxEvent(
        DomainEvent event,
        OutboxSource source,
        UUID claimId,
        int attempts) {
}
