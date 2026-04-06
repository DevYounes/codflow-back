-- Système de réservation de stock pour les commandes COD
-- reservedStock = stock réservé par des commandes confirmées mais pas encore livrées
-- availableStock (calculé) = currentStock - reservedStock
-- Flux :
--   CONFIRME  → reservedStock += qty        (stock réservé, non disponible)
--   LIVRE     → currentStock -= qty, reservedStock -= qty  (déduction finale)
--   RETOURNE/ANNULÉ avant LIVRE → reservedStock -= qty    (libération réservation)
--   RETOURNE après LIVRE        → currentStock += qty      (retour physique)

ALTER TABLE products
    ADD COLUMN reserved_stock INT NOT NULL DEFAULT 0;

ALTER TABLE product_variants
    ADD COLUMN reserved_stock INT NOT NULL DEFAULT 0;

-- Champ sur les commandes : true quand le stock est réservé (post-CONFIRME, pré-LIVRE)
ALTER TABLE orders
    ADD COLUMN stock_reserved BOOLEAN NOT NULL DEFAULT FALSE;

-- Les commandes déjà en stock_deducted=true (confirmées avec l'ancienne logique) :
-- leur stock est déjà soustrait de currentStock → reserved_stock reste 0, comportement correct.
