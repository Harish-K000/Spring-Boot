CREATE SCHEMA IF NOT EXISTS worker;

CREATE TABLE worker.processed_events (
    event_id UUID PRIMARY KEY,
    source VARCHAR(20) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE worker.event_audit (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    source VARCHAR(20) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_audit_processed
        FOREIGN KEY (event_id) REFERENCES worker.processed_events(event_id)
);

CREATE INDEX idx_worker_event_audit_aggregate
    ON worker.event_audit (aggregate_type, aggregate_id, occurred_at);
