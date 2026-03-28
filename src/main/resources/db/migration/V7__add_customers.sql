-- ── Customers table ──────────────────────────────────────────────────────────
CREATE TABLE customers (
    id               BIGSERIAL PRIMARY KEY,
    full_name        VARCHAR(200) NOT NULL,
    phone            VARCHAR(20)  NOT NULL,
    phone_normalized VARCHAR(20),
    email            VARCHAR(200),
    address          TEXT,
    ville            VARCHAR(100),
    status           VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    notes            TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customers_phone_normalized ON customers(phone_normalized);
CREATE INDEX idx_customers_status           ON customers(status);

-- ── Add customer FK to orders ─────────────────────────────────────────────────
ALTER TABLE orders ADD COLUMN customer_id BIGINT REFERENCES customers(id) ON DELETE SET NULL;
CREATE INDEX idx_orders_customer_id ON orders(customer_id);

-- ── Backfill: create one customer per unique phone_normalized ─────────────────
WITH first_order AS (
    SELECT
        customer_name,
        customer_phone,
        customer_phone_normalized,
        address,
        ville,
        created_at,
        ROW_NUMBER() OVER (
            PARTITION BY customer_phone_normalized
            ORDER BY created_at ASC
        ) AS rn
    FROM orders
    WHERE customer_phone_normalized IS NOT NULL
)
INSERT INTO customers (full_name, phone, phone_normalized, address, ville, status, created_at, updated_at)
SELECT customer_name, customer_phone, customer_phone_normalized, address, ville, 'ACTIVE', created_at, NOW()
FROM first_order
WHERE rn = 1;

-- ── Backfill: orders without normalized phone (one customer per order) ────────
INSERT INTO customers (full_name, phone, phone_normalized, address, ville, status, created_at, updated_at)
SELECT customer_name, customer_phone, customer_phone_normalized, address, ville, 'ACTIVE', created_at, NOW()
FROM orders
WHERE customer_phone_normalized IS NULL;

-- ── Link existing orders to their customers ───────────────────────────────────
UPDATE orders o
SET customer_id = c.id
FROM customers c
WHERE o.customer_phone_normalized = c.phone_normalized
  AND o.customer_phone_normalized IS NOT NULL;

UPDATE orders o
SET customer_id = c.id
FROM customers c
WHERE o.customer_id IS NULL
  AND o.customer_phone_normalized IS NULL
  AND o.customer_phone = c.phone
  AND o.customer_name  = c.full_name;
