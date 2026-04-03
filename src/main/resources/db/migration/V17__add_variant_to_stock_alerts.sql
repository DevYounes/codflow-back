-- Add variant_id to stock_alerts for per-variant stock alerting
-- variant_id NULL = product-level alert; variant_id NOT NULL = variant-level alert
ALTER TABLE stock_alerts
    ADD COLUMN variant_id BIGINT REFERENCES product_variants(id);

CREATE INDEX idx_stock_alerts_variant_id ON stock_alerts(variant_id);
