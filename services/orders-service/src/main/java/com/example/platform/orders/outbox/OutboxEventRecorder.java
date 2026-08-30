package com.example.platform.orders.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Persists domain events on the caller's business transaction. */
@Component
public class OutboxEventRecorder {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public OutboxEventRecorder(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    public void record(UUID aggregateId, String eventType, Map<String, ?> payload) {
        try {
            entityManager.persist(new OutboxEvent(
                    "ORDER", aggregateId, eventType, objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize order event " + eventType, ex);
        }
    }
}
