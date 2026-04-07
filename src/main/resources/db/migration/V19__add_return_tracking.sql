-- Suivi des retours physiques
-- Problème : la société de livraison peut marquer un colis comme "refusé" ou "retourné"
-- dans son système sans jamais le ramener physiquement au marchand.
-- Ces colonnes permettent de confirmer la réception physique du retour.

ALTER TABLE delivery_shipments
    ADD COLUMN return_received       BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN return_received_at    TIMESTAMP,
    ADD COLUMN return_received_notes VARCHAR(500);

-- Index pour accélérer la requête "colis à retours en attente"
CREATE INDEX idx_shipments_pending_return
    ON delivery_shipments (status, return_received)
    WHERE status IN ('FAILED_DELIVERY', 'RETURNED', 'CANCELLED')
      AND return_received = FALSE;
