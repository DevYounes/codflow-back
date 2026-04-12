-- Paiements fournisseurs
CREATE TABLE supplier_payments (
    id                BIGSERIAL PRIMARY KEY,
    supplier_order_id BIGINT NOT NULL REFERENCES supplier_orders(id) ON DELETE CASCADE,
    amount            NUMERIC(12,2) NOT NULL,
    payment_date      DATE NOT NULL,
    payment_method    VARCHAR(30) NOT NULL DEFAULT 'ESPECES',
    reference         VARCHAR(100),
    notes             TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Réceptions (lots)
CREATE TABLE supplier_deliveries (
    id                BIGSERIAL PRIMARY KEY,
    supplier_order_id BIGINT NOT NULL REFERENCES supplier_orders(id) ON DELETE CASCADE,
    lot_number        VARCHAR(100),
    delivery_date     DATE NOT NULL,
    notes             TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Lignes de réception
CREATE TABLE supplier_delivery_items (
    id                      BIGSERIAL PRIMARY KEY,
    supplier_delivery_id    BIGINT NOT NULL REFERENCES supplier_deliveries(id) ON DELETE CASCADE,
    supplier_order_item_id  BIGINT NOT NULL REFERENCES supplier_order_items(id),
    quantity_received        INT NOT NULL,
    unit_cost               NUMERIC(10,2) NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_supplier_payments_order   ON supplier_payments(supplier_order_id);
CREATE INDEX idx_supplier_deliveries_order ON supplier_deliveries(supplier_order_id);
CREATE INDEX idx_supplier_delivery_items_delivery ON supplier_delivery_items(supplier_delivery_id);
