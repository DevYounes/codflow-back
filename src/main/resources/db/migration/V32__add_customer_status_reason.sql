-- Motif/libellé associé au statut client (ex : raison du blacklist).
-- Distinct du champ libre `notes`, ce champ est destiné à être affiché à
-- côté du statut dans l'UI pour expliquer pourquoi un client a été flagué.
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS status_reason VARCHAR(255);
