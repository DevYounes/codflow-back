-- V21: Track delivery cancellation attempts per shipment (Ozon "Annulé" events).
-- Each time the carrier marks a delivery as cancelled, this counter is incremented.
-- Used to auto-flag customers as NON_SERIEUX after repeated refusals.
ALTER TABLE delivery_shipments
    ADD COLUMN IF NOT EXISTS cancelled_attempts INT NOT NULL DEFAULT 0;
