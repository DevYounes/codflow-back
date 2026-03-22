-- V4: Add duplicate detection fields to orders
-- potential_duplicate: flags orders sharing the same normalized phone number
-- customer_phone_normalized: canonical 9-digit form (e.g. 0612345678 / +212612345678 / 212612345678 → 612345678)

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS potential_duplicate     BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS customer_phone_normalized VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_orders_phone_normalized ON orders(customer_phone_normalized);
