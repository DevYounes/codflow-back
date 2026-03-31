-- Coût de revient sur les produits et variantes
ALTER TABLE products ADD COLUMN cost_price DECIMAL(10,2);
ALTER TABLE product_variants ADD COLUMN cost_price DECIMAL(10,2);
