package com.example.platform.worker.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "worker.scheduling.enabled=false")
class JdbcOutboxStoreIntegrationTest {

    @Autowired private JdbcOutboxStore store;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void createSourceSchemas() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS orders");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS payments");
        createOutbox("orders.outbox_events");
        createOutbox("payments.outbox_events");
        jdbc.update("DELETE FROM orders.outbox_events");
        jdbc.update("DELETE FROM payments.outbox_events");
    }

    @Test
    void claimsAcrossSourcesAndPersistsPublicationOrRetryState() {
        UUID orderEvent = insert("orders.outbox_events", "ORDER", "ORDER_CREATED");
        UUID paymentEvent = insert("payments.outbox_events", "PAYMENT", "PAYMENT_CAPTURED");

        var claimed = store.claimBatch(10);

        assertThat(claimed).extracting(row -> row.event().id())
                .containsExactlyInAnyOrder(orderEvent, paymentEvent);

        ClaimedOutboxEvent published = claimed.stream()
                .filter(row -> row.event().id().equals(orderEvent)).findFirst().orElseThrow();
        ClaimedOutboxEvent failed = claimed.stream()
                .filter(row -> row.event().id().equals(paymentEvent)).findFirst().orElseThrow();
        store.markPublished(published);
        store.markFailed(failed, new RuntimeException("redis unavailable"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders.outbox_events
                WHERE id = ? AND published_at IS NOT NULL AND claim_id IS NULL
                """, Long.class, orderEvent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM payments.outbox_events
                WHERE id = ? AND attempts = 1 AND next_attempt_at > CURRENT_TIMESTAMP
                  AND last_error = 'redis unavailable' AND claim_id IS NULL
                """, Long.class, paymentEvent)).isEqualTo(1);
    }

    private UUID insert(String table, String aggregateType, String eventType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table + " "
                        + "(id, aggregate_type, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?, ?)",
                id, aggregateType, UUID.randomUUID(), eventType, "{\"status\":\"READY\"}");
        return id;
    }

    private void createOutbox(String table) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id UUID PRIMARY KEY,
                    aggregate_type VARCHAR(50) NOT NULL,
                    aggregate_id UUID NOT NULL,
                    event_type VARCHAR(100) NOT NULL,
                    payload TEXT NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    published_at TIMESTAMP WITH TIME ZONE,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    claim_id UUID,
                    claimed_at TIMESTAMP WITH TIME ZONE,
                    last_error VARCHAR(500)
                )
                """.formatted(table));
    }
}
