-- =============================================================================
-- CODFlow - Initial Database Schema
-- =============================================================================

-- -----------------------------------------------
-- USERS (Team Members)
-- -----------------------------------------------
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  UNIQUE NOT NULL,
    email       VARCHAR(100) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'AGENT',
    phone       VARCHAR(20),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_active ON users(active);

-- -----------------------------------------------
-- PRODUCTS
-- -----------------------------------------------
CREATE TABLE products (
    id              BIGSERIAL    PRIMARY KEY,
    sku             VARCHAR(100) UNIQUE NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    price           DECIMAL(10,2) NOT NULL DEFAULT 0,
    image_url       VARCHAR(500),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    current_stock   INTEGER      NOT NULL DEFAULT 0,
    min_threshold   INTEGER      NOT NULL DEFAULT 10,
    alert_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_sku ON products(sku);
CREATE INDEX idx_products_active ON products(active);

-- -----------------------------------------------
-- DELIVERY PROVIDERS
-- -----------------------------------------------
CREATE TABLE delivery_providers (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    code         VARCHAR(50)  UNIQUE NOT NULL,
    api_base_url VARCHAR(500),
    api_key      VARCHAR(500),
    api_token    VARCHAR(500),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    config       JSONB,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------
-- ORDERS
-- -----------------------------------------------
CREATE TABLE orders (
    id               BIGSERIAL    PRIMARY KEY,
    order_number     VARCHAR(100) UNIQUE NOT NULL,
    source           VARCHAR(20)  NOT NULL DEFAULT 'EXCEL',
    -- Customer info
    customer_name    VARCHAR(255) NOT NULL,
    customer_phone   VARCHAR(20)  NOT NULL,
    customer_phone2  VARCHAR(20),
    address          TEXT         NOT NULL,
    city             VARCHAR(100) NOT NULL,
    zip_code         VARCHAR(10),
    notes            TEXT,
    -- Amounts
    subtotal         DECIMAL(10,2) NOT NULL DEFAULT 0,
    shipping_cost    DECIMAL(10,2) NOT NULL DEFAULT 0,
    total_amount     DECIMAL(10,2) NOT NULL DEFAULT 0,
    -- Status
    status           VARCHAR(50)  NOT NULL DEFAULT 'NOUVEAU',
    -- Assignment
    assigned_to      BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    assigned_at      TIMESTAMP,
    -- Lifecycle timestamps
    confirmed_at     TIMESTAMP,
    cancelled_at     TIMESTAMP,
    -- External references
    shopify_order_id VARCHAR(100),
    external_ref     VARCHAR(100),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_assigned_to ON orders(assigned_to);
CREATE INDEX idx_orders_created_at ON orders(created_at);
CREATE INDEX idx_orders_customer_phone ON orders(customer_phone);
CREATE INDEX idx_orders_order_number ON orders(order_number);
CREATE INDEX idx_orders_source ON orders(source);

-- -----------------------------------------------
-- ORDER ITEMS
-- -----------------------------------------------
CREATE TABLE order_items (
    id           BIGSERIAL    PRIMARY KEY,
    order_id     BIGINT       NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id   BIGINT       REFERENCES products(id) ON DELETE SET NULL,
    product_name VARCHAR(255) NOT NULL,
    product_sku  VARCHAR(100),
    quantity     INTEGER      NOT NULL DEFAULT 1,
    unit_price   DECIMAL(10,2) NOT NULL DEFAULT 0,
    total_price  DECIMAL(10,2) NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

-- -----------------------------------------------
-- ORDER STATUS HISTORY
-- -----------------------------------------------
CREATE TABLE order_status_history (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status VARCHAR(50),
    to_status   VARCHAR(50) NOT NULL,
    changed_by  BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    notes       TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_status_history_order_id ON order_status_history(order_id);

-- -----------------------------------------------
-- DELIVERY SHIPMENTS
-- -----------------------------------------------
CREATE TABLE delivery_shipments (
    id                    BIGSERIAL    PRIMARY KEY,
    order_id              BIGINT       NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    provider_id           BIGINT       NOT NULL REFERENCES delivery_providers(id),
    tracking_number       VARCHAR(100),
    provider_order_id     VARCHAR(100),
    status                VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    pickup_requested      BOOLEAN      NOT NULL DEFAULT FALSE,
    pickup_requested_at   TIMESTAMP,
    shipped_at            TIMESTAMP,
    out_for_delivery_at   TIMESTAMP,
    delivered_at          TIMESTAMP,
    returned_at           TIMESTAMP,
    notes                 TEXT,
    raw_response          JSONB,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shipments_order_id ON delivery_shipments(order_id);
CREATE INDEX idx_shipments_tracking ON delivery_shipments(tracking_number);
CREATE INDEX idx_shipments_status ON delivery_shipments(status);

-- -----------------------------------------------
-- DELIVERY TRACKING HISTORY
-- -----------------------------------------------
CREATE TABLE delivery_tracking_history (
    id          BIGSERIAL   PRIMARY KEY,
    shipment_id BIGINT      NOT NULL REFERENCES delivery_shipments(id) ON DELETE CASCADE,
    status      VARCHAR(50) NOT NULL,
    description TEXT,
    location    VARCHAR(255),
    event_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tracking_shipment_id ON delivery_tracking_history(shipment_id);

-- -----------------------------------------------
-- STOCK MOVEMENTS
-- -----------------------------------------------
CREATE TABLE stock_movements (
    id              BIGSERIAL   PRIMARY KEY,
    product_id      BIGINT      NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    movement_type   VARCHAR(20) NOT NULL,
    quantity        INTEGER     NOT NULL,
    previous_stock  INTEGER     NOT NULL,
    new_stock       INTEGER     NOT NULL,
    reason          VARCHAR(255),
    reference_type  VARCHAR(50),
    reference_id    BIGINT,
    created_by      BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_movements_product_id ON stock_movements(product_id);
CREATE INDEX idx_stock_movements_type ON stock_movements(movement_type);
CREATE INDEX idx_stock_movements_created_at ON stock_movements(created_at);

-- -----------------------------------------------
-- STOCK ALERTS
-- -----------------------------------------------
CREATE TABLE stock_alerts (
    id            BIGSERIAL   PRIMARY KEY,
    product_id    BIGINT      NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    alert_type    VARCHAR(30) NOT NULL,
    threshold     INTEGER     NOT NULL,
    current_level INTEGER     NOT NULL,
    resolved      BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_at   TIMESTAMP,
    resolved_by   BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_alerts_product_id ON stock_alerts(product_id);
CREATE INDEX idx_stock_alerts_resolved ON stock_alerts(resolved);

-- -----------------------------------------------
-- SEED DATA
-- -----------------------------------------------

-- Default admin user (password will be set by DataInitializer)
-- Ozon Express delivery provider
INSERT INTO delivery_providers (name, code, api_base_url, active)
VALUES ('Ozon Express', 'OZON_EXPRESS', 'https://api.ozonexpress.ma', TRUE);
