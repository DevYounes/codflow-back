-- Type de produit pour distinguer les articles vendus (PRODUIT) des
-- consommables internes (CONSOMMABLE) : emballages, étiquettes, etc.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS type VARCHAR(20) NOT NULL DEFAULT 'PRODUIT';

CREATE INDEX IF NOT EXISTS idx_products_type ON products(type);
