ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS delivery_notes TEXT;
