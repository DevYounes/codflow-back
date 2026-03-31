-- Arrivages de stock (réapprovisionnements)
CREATE TABLE stock_arrivals (
    id          BIGSERIAL PRIMARY KEY,
    reference   VARCHAR(100) UNIQUE NOT NULL,
    product_id  BIGINT NOT NULL REFERENCES products(id),
    arrived_at  DATE NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    created_by_id BIGINT REFERENCES users(id)
);

-- Lignes d'un arrivage (une ligne par variante ou par produit si pas de variante)
CREATE TABLE stock_arrival_items (
    id          BIGSERIAL PRIMARY KEY,
    arrival_id  BIGINT NOT NULL REFERENCES stock_arrivals(id) ON DELETE CASCADE,
    variant_id  BIGINT REFERENCES product_variants(id),
    quantity    INT NOT NULL CHECK (quantity >= 0),
    unit_cost   DECIMAL(10,2),
    created_at  TIMESTAMP
);
