-- -----------------------------------------------
-- DELIVERY NOTES (Bons de Livraison)
-- -----------------------------------------------
CREATE TABLE delivery_notes (
    id          BIGSERIAL    PRIMARY KEY,
    ref         VARCHAR(100) NOT NULL UNIQUE,
    provider_id BIGINT       NOT NULL REFERENCES delivery_providers(id),
    status      VARCHAR(30)  NOT NULL DEFAULT 'SAVED',
    notes       TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Join table: which shipments belong to which BL
CREATE TABLE delivery_note_shipments (
    delivery_note_id BIGINT NOT NULL REFERENCES delivery_notes(id) ON DELETE CASCADE,
    shipment_id      BIGINT NOT NULL REFERENCES delivery_shipments(id),
    PRIMARY KEY (delivery_note_id, shipment_id)
);

CREATE INDEX idx_delivery_notes_ref         ON delivery_notes(ref);
CREATE INDEX idx_delivery_notes_provider_id ON delivery_notes(provider_id);
CREATE INDEX idx_delivery_note_shipments_shipment ON delivery_note_shipments(shipment_id);
