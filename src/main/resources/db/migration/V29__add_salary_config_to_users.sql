-- Configuration salariale par utilisateur
--
-- salary_type :
--   * FIXE                    : salaire fixe seulement
--   * COMMISSION              : commission seule (agents uniquement)
--   * FIXE_PLUS_COMMISSION    : salaire fixe + commission
--
-- commission_type (null si pas de commission) :
--   * PAR_CONFIRME            : commission par commande confirmée (agent)
--   * PAR_LIVRE               : commission par commande livrée (agent / magasinier)
--   * CONFIRME_ET_LIVRE       : deux commissions cumulées (agent uniquement)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS salary_type VARCHAR(30) NOT NULL DEFAULT 'FIXE',
    ADD COLUMN IF NOT EXISTS fixed_salary NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS commission_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS commission_per_confirmed NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS commission_per_delivered NUMERIC(10,2);
