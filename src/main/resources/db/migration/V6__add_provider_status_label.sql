ALTER TABLE delivery_shipments
    ADD COLUMN IF NOT EXISTS provider_status_label VARCHAR(100);
