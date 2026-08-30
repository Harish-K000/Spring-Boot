ALTER TABLE orders.orders
    ADD COLUMN idempotency_key VARCHAR(100);

ALTER TABLE orders.orders
    ADD COLUMN checkout_fingerprint VARCHAR(64);

ALTER TABLE orders.orders
    ADD CONSTRAINT uk_orders_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE orders.orders
    ADD CONSTRAINT ck_orders_total_amount_nonnegative CHECK (total_amount >= 0.00);

CREATE TABLE orders.order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders.orders(id),
    product_id UUID NOT NULL,
    sku VARCHAR(100) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,
    line_total NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price_positive CHECK (unit_price > 0.00),
    CONSTRAINT ck_order_items_line_total_nonnegative CHECK (line_total >= 0.00),
    CONSTRAINT uk_order_items_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_order_items_order_id ON orders.order_items(order_id);
CREATE INDEX idx_orders_user_created_at ON orders.orders(user_id, created_at);
