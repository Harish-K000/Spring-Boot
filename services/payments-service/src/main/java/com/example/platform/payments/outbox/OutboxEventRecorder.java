package com.example.platform.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

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
                    "PAYMENT", aggregateId, eventType, objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize payment event " + eventType, ex);
        }
    }
}
