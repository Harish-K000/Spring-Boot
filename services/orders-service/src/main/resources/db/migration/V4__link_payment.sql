ALTER TABLE orders.orders
    ADD COLUMN payment_id UUID;

ALTER TABLE orders.orders
    ADD CONSTRAINT uk_orders_payment_id UNIQUE (payment_id);
