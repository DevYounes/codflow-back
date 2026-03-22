-- V3: Create product_variants table, add variant_id to order_items, create system_settings

CREATE TABLE system_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT,
    description   VARCHAR(255),
    updated_at    TIMESTAMP
);

CREATE TABLE product_variants (
    id            BIGSERIAL    PRIMARY KEY,
    product_id    BIGINT       NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    color         VARCHAR(100),
    size          VARCHAR(50),
    variant_sku   VARCHAR(100),
    price_override DECIMAL(10,2),
    current_stock INTEGER      NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_variant_product_color_size UNIQUE (product_id, color, size)
);

CREATE INDEX idx_product_variants_product_id ON product_variants(product_id);

ALTER TABLE order_items
    ADD COLUMN variant_id BIGINT REFERENCES product_variants(id) ON DELETE SET NULL;

CREATE INDEX idx_order_items_variant_id ON order_items(variant_id);
