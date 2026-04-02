-- Charges opérationnelles du business (pub, loyer, salaires, factures, etc.)
CREATE TABLE business_charges (
    id           BIGSERIAL PRIMARY KEY,
    type         VARCHAR(30)    NOT NULL,   -- PUBLICITE, LOYER, SALAIRE, ELECTRICITE, EAU, INTERNET, AUTRE
    label        VARCHAR(200)   NOT NULL,   -- description libre (ex: "Facebook Ads Février", "Loyer entrepôt")
    amount       DECIMAL(10,2)  NOT NULL CHECK (amount >= 0),
    charge_date  DATE           NOT NULL,   -- date de la charge (permet filtrage par période)
    notes        TEXT,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by_id BIGINT REFERENCES users(id)
);

CREATE INDEX idx_business_charges_type ON business_charges(type);
CREATE INDEX idx_business_charges_date ON business_charges(charge_date);
