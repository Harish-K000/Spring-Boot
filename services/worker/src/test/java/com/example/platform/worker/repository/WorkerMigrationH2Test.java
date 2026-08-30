package com.example.platform.worker.repository;

import com.example.platform.worker.consumer.EventProcessingService;
import com.example.platform.worker.event.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workermigrations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.create-schemas=true",
        "spring.flyway.schemas=worker",
        "spring.flyway.default-schema=worker",
        "worker.scheduling.enabled=false"
})
class WorkerMigrationH2Test {

    @Autowired private EventProcessingService processingService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void migrationsSupportIdempotentEventProcessing() {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID(), "orders", "ORDER", UUID.randomUUID(),
                "ORDER_CREATED", "{\"status\":\"PENDING\"}", Instant.now());

        assertThat(processingService.process(event)).isTrue();
        assertThat(processingService.process(event)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM worker.processed_events WHERE event_id = ?",
                Long.class, event.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM worker.event_audit WHERE event_id = ?",
                Long.class, event.id())).isEqualTo(1);
    }
}
