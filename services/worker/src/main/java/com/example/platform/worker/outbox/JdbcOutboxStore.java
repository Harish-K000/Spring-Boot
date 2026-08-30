package com.example.platform.worker.outbox;

import com.example.platform.worker.event.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;
    private final long claimTimeoutSeconds;

    public JdbcOutboxStore(
            JdbcTemplate jdbc,
            @Value("${worker.relay.claim-timeout-seconds:60}") long claimTimeoutSeconds) {
        this.jdbc = jdbc;
        this.claimTimeoutSeconds = claimTimeoutSeconds;
    }

    @Override
    @Transactional
    public List<ClaimedOutboxEvent> claimBatch(int batchSize) {
        UUID claimId = UUID.randomUUID();
        Instant staleBefore = Instant.now().minusSeconds(claimTimeoutSeconds);
        List<ClaimedOutboxEvent> claimed = new ArrayList<>();
        for (OutboxSource source : OutboxSource.values()) {
            int remaining = batchSize - claimed.size();
            if (remaining <= 0) {
                break;
            }
            String select = """
                    SELECT id, aggregate_type, aggregate_id, event_type, payload,
                           occurred_at, attempts
                    FROM %s
                    WHERE published_at IS NULL
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (claim_id IS NULL OR claimed_at < ?)
                    ORDER BY occurred_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """.formatted(source.table());
            List<ClaimedOutboxEvent> rows = jdbc.query(select, (rs, row) ->
                    new ClaimedOutboxEvent(
                            new DomainEvent(
                                    rs.getObject("id", UUID.class),
                                    source.eventSource(),
                                    rs.getString("aggregate_type"),
                                    rs.getObject("aggregate_id", UUID.class),
                                    rs.getString("event_type"),
                                    rs.getString("payload"),
                                    rs.getTimestamp("occurred_at").toInstant()),
                            source, claimId, rs.getInt("attempts")),
                    Timestamp.from(staleBefore), remaining);
            for (ClaimedOutboxEvent row : rows) {
                jdbc.update("UPDATE " + source.table()
                                + " SET claim_id = ?, claimed_at = CURRENT_TIMESTAMP WHERE id = ?",
                        claimId, row.event().id());
            }
            claimed.addAll(rows);
        }
        return claimed;
    }

    @Override
    @Transactional
    public void markPublished(ClaimedOutboxEvent event) {
        jdbc.update("UPDATE " + event.source().table() + " SET published_at = CURRENT_TIMESTAMP, "
                        + "claim_id = NULL, claimed_at = NULL, last_error = NULL WHERE id = ? AND claim_id = ?",
                event.event().id(), event.claimId());
    }

    @Override
    @Transactional
    public void markFailed(ClaimedOutboxEvent event, RuntimeException failure) {
        long delaySeconds = Math.min(300, 1L << Math.min(event.attempts(), 8));
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbc.update("UPDATE " + event.source().table() + " SET attempts = attempts + 1, "
                        + "next_attempt_at = ?, last_error = ?, claim_id = NULL, claimed_at = NULL "
                        + "WHERE id = ? AND claim_id = ?",
                Timestamp.from(Instant.now().plusSeconds(delaySeconds)), message,
                event.event().id(), event.claimId());
    }
}
