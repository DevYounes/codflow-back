-- V22: Track delivery refusal attempts per shipment (Ozon "Refusé" events).
-- Incremented each time the customer explicitly refuses the package at the door.
-- Distinct from cancelled_attempts (carrier cancellation) — a refusal is a deliberate
-- customer action that costs 10 MAD in carrier fees and may signal a non-serious customer.
ALTER TABLE delivery_shipments
    ADD COLUMN IF NOT EXISTS refused_attempts INT NOT NULL DEFAULT 0;
