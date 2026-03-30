-- Delivery fee columns on shipments
-- delivery_fee : tarif livraison de la ville (récupéré depuis l'API Ozon au moment de la création)
-- return_fee   : tarif retour/refus de la ville
-- applied_fee  : frais réellement facturés selon le statut final (livré → delivery_fee, retourné → return_fee)
-- applied_fee_type : 'LIVRAISON', 'RETOUR', 'ANNULATION', null (pas encore finalisé)

ALTER TABLE delivery_shipments
    ADD COLUMN delivery_fee      DECIMAL(10,2),
    ADD COLUMN return_fee        DECIMAL(10,2),
    ADD COLUMN applied_fee       DECIMAL(10,2),
    ADD COLUMN applied_fee_type  VARCHAR(20);
