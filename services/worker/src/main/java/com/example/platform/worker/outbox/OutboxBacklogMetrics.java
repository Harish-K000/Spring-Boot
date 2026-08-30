package com.example.platform.worker.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);
    private final JdbcTemplate jdbc;

    public OutboxBacklogMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (OutboxSource source : OutboxSource.values()) {
            Gauge.builder("platform.outbox.pending", source, this::pendingCount)
                    .description("Unpublished source outbox events")
                    .tag("source", source.eventSource())
                    .register(registry);
            Gauge.builder("platform.outbox.oldest.age", source, this::oldestAgeSeconds)
                    .description("Age in seconds of the oldest unpublished outbox event")
                    .baseUnit("seconds")
                    .tag("source", source.eventSource())
                    .register(registry);
        }
    }

    private double pendingCount(OutboxSource source) {
        return query(source, "SELECT COUNT(*) FROM " + source.table()
                + " WHERE published_at IS NULL");
    }

    private double oldestAgeSeconds(OutboxSource source) {
        return query(source, "SELECT COALESCE(EXTRACT(EPOCH FROM "
                + "(CURRENT_TIMESTAMP - MIN(occurred_at))), 0) FROM " + source.table()
                + " WHERE published_at IS NULL");
    }

    private double query(OutboxSource source, String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException ex) {
            log.debug("Could not sample {} outbox backlog", source.eventSource(), ex);
            return Double.NaN;
        }
    }
}
