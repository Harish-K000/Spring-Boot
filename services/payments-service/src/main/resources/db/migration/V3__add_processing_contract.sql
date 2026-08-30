ALTER TABLE payments.payment_transactions
    ADD COLUMN user_id UUID;

ALTER TABLE payments.payment_transactions
    ADD COLUMN active_order_id UUID;

UPDATE payments.payment_transactions
SET active_order_id = order_id
WHERE status IN ('PENDING', 'AUTHORIZED', 'CAPTURED');

ALTER TABLE payments.payment_transactions
    ADD COLUMN idempotency_key VARCHAR(100);

ALTER TABLE payments.payment_transactions
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE payments.payment_transactions
    ADD COLUMN provider_reference VARCHAR(100);

ALTER TABLE payments.payment_transactions
    ADD COLUMN failure_code VARCHAR(50);

ALTER TABLE payments.payment_transactions
    ADD CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE payments.payment_transactions
    ADD CONSTRAINT uk_payments_active_order UNIQUE (active_order_id);

ALTER TABLE payments.payment_transactions
    ADD CONSTRAINT ck_payments_amount_positive CHECK (amount > 0.00);

CREATE INDEX idx_payments_order_id ON payments.payment_transactions(order_id);
CREATE INDEX idx_payments_user_created_at ON payments.payment_transactions(user_id, created_at);
