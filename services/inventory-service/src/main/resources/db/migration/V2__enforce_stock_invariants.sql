UPDATE inventory.products
SET sku = UPPER(TRIM(sku)),
    name = TRIM(name);

ALTER TABLE inventory.products
    ADD CONSTRAINT ck_products_quantity_on_hand_nonnegative
        CHECK (quantity_on_hand >= 0);

ALTER TABLE inventory.products
    ADD CONSTRAINT ck_products_reserved_quantity_nonnegative
        CHECK (reserved_quantity >= 0);

ALTER TABLE inventory.products
    ADD CONSTRAINT ck_products_reserved_not_above_on_hand
        CHECK (reserved_quantity <= quantity_on_hand);
