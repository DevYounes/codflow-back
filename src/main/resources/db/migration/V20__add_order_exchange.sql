-- Gestion des échanges
-- Un échange = nouvelle commande créée à partir d'une commande livrée,
-- avec un nouveau produit (même client, même adresse).
-- Le flag is_exchange permet à la société de livraison (Ozon) de savoir
-- qu'il s'agit d'un échange (parcel-echange=1) et non d'un nouveau colis.

ALTER TABLE orders
    ADD COLUMN is_exchange       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN source_order_id   BIGINT  REFERENCES orders(id);

CREATE INDEX idx_orders_source ON orders (source_order_id) WHERE source_order_id IS NOT NULL;
