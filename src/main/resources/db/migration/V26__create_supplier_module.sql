-- Fournisseurs
CREATE TABLE suppliers (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    phone       VARCHAR(30),
    email       VARCHAR(150),
    address     TEXT,
    notes       TEXT,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Bons de commande fournisseur
CREATE TABLE supplier_orders (
    id                     BIGSERIAL PRIMARY KEY,
    order_number           VARCHAR(100) NOT NULL UNIQUE,
    supplier_id            BIGINT NOT NULL REFERENCES suppliers(id),
    status                 VARCHAR(30) NOT NULL DEFAULT 'BROUILLON',
    order_date             DATE NOT NULL,
    expected_delivery_date DATE,
    total_amount           NUMERIC(12,2) NOT NULL DEFAULT 0,
    paid_amount            NUMERIC(12,2) NOT NULL DEFAULT 0,
    notes                  TEXT,
    created_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Lignes de commande fournisseur
CREATE TABLE supplier_order_items (
    id                 BIGSERIAL PRIMARY KEY,
    supplier_order_id  BIGINT NOT NULL REFERENCES supplier_orders(id) ON DELETE CASCADE,
    product_id         BIGINT REFERENCES products(id),
    variant_id         BIGINT REFERENCES product_variants(id),
    product_name       VARCHAR(200) NOT NULL,
    product_sku        VARCHAR(100),
    quantity_ordered   INT NOT NULL,
    quantity_received  INT NOT NULL DEFAULT 0,
    unit_cost          NUMERIC(10,2) NOT NULL,
    total_cost         NUMERIC(12,2) NOT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_supplier_orders_supplier ON supplier_orders(supplier_id);
CREATE INDEX idx_supplier_orders_status   ON supplier_orders(status);
CREATE INDEX idx_supplier_order_items_order ON supplier_order_items(supplier_order_id);
