-- CHAR(3) is blank-padded by PostgreSQL and reports JDBC type CHAR, which Hibernate's schema
-- validator rejects against a String mapping. VARCHAR(3) stores the same ISO-4217 codes without
-- the padding surprise.
ALTER TABLE orders.orders ALTER COLUMN currency TYPE VARCHAR(3);
