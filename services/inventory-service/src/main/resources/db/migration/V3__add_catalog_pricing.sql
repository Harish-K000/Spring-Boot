ALTER TABLE inventory.products
    ADD COLUMN unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0.00;

ALTER TABLE inventory.products
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD';

ALTER TABLE inventory.products
    ADD CONSTRAINT ck_products_unit_price_nonnegative
        CHECK (unit_price >= 0.00);
