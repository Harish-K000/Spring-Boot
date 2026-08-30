CREATE TABLE orders.outbox_events (
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
    last_error VARCHAR(500),
    CONSTRAINT chk_orders_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_orders_outbox_ready
    ON orders.outbox_events (published_at, next_attempt_at, occurred_at);
CREATE INDEX idx_orders_outbox_claim
    ON orders.outbox_events (claim_id, claimed_at);
