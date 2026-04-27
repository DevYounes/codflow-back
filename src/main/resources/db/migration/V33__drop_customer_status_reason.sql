-- Retour arrière de V32 : le statut client est désormais 100% manuel et seule
-- la note libre `notes` est utilisée pour mentionner le motif. Le champ
-- status_reason devenait redondant.
ALTER TABLE customers
    DROP COLUMN IF EXISTS status_reason;
