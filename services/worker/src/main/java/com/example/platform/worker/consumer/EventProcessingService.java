package com.example.platform.worker.consumer;

import com.example.platform.worker.event.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class EventProcessingService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean h2;

    public EventProcessingService(JdbcTemplate jdbc, ObjectMapper objectMapper, DataSource dataSource) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.h2 = isH2(dataSource);
    }

    /** Returns false for a duplicate that was already committed by any worker instance. */
    @Transactional
    public boolean process(DomainEvent event) {
        validate(event);
        int inserted = h2
                ? jdbc.update("""
                        INSERT INTO worker.processed_events (event_id, source, event_type)
                        SELECT ?, ?, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM worker.processed_events WHERE event_id = ?
                        )
                        """, event.id(), event.source(), event.eventType(), event.id())
                : jdbc.update("""
                        INSERT INTO worker.processed_events (event_id, source, event_type)
                        VALUES (?, ?, ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """, event.id(), event.source(), event.eventType());
        if (inserted == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO worker.event_audit
                    (id, event_id, source, aggregate_type, aggregate_id,
                     event_type, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), event.id(), event.source(), event.aggregateType(),
                event.aggregateId(), event.eventType(), event.payload(), event.occurredAt());
        return true;
    }

    private void validate(DomainEvent event) {
        if (event.id() == null || event.aggregateId() == null || event.occurredAt() == null
                || blank(event.source()) || blank(event.aggregateType()) || blank(event.eventType())
                || blank(event.payload())) {
            throw new IllegalArgumentException("Domain event is incomplete");
        }
        try {
            objectMapper.readTree(event.payload());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Domain event payload is not valid JSON", ex);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isH2(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not determine worker database type", ex);
        }
    }
}
