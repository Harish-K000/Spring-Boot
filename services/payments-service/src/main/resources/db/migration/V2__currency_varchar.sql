-- See orders V2: CHAR(3) reports JDBC type CHAR and is blank-padded; VARCHAR(3) avoids both.
ALTER TABLE payments.payment_transactions ALTER COLUMN currency TYPE VARCHAR(3);
