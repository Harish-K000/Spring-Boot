package com.example.platform.worker.outbox;

import java.util.List;

public interface OutboxStore {
    List<ClaimedOutboxEvent> claimBatch(int batchSize);
    void markPublished(ClaimedOutboxEvent event);
    void markFailed(ClaimedOutboxEvent event, RuntimeException failure);
}
