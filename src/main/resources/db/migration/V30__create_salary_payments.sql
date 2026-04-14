-- Paiements de salaires (fiches de paie)
CREATE TABLE salary_payments (
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT NOT NULL REFERENCES users(id),
    period_start              DATE NOT NULL,
    period_end                DATE NOT NULL,

    -- Snapshot de la configuration salariale au moment de la génération
    salary_type               VARCHAR(30) NOT NULL,
    commission_type           VARCHAR(30),
    fixed_salary              NUMERIC(12,2) NOT NULL DEFAULT 0,
    commission_per_confirmed  NUMERIC(10,2),
    commission_per_delivered  NUMERIC(10,2),

    -- Snapshot des compteurs qui ont servi au calcul
    confirmed_count           INT NOT NULL DEFAULT 0,
    delivered_count           INT NOT NULL DEFAULT 0,

    -- Montants calculés
    commission_amount         NUMERIC(12,2) NOT NULL DEFAULT 0,
    bonus                     NUMERIC(12,2) NOT NULL DEFAULT 0,
    deduction                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount              NUMERIC(12,2) NOT NULL DEFAULT 0,

    -- Statut et règlement
    status                    VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    payment_date              DATE,
    payment_method            VARCHAR(30),
    reference                 VARCHAR(100),
    notes                     TEXT,

    created_by_id             BIGINT REFERENCES users(id),
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_salary_payments_user        ON salary_payments(user_id);
CREATE INDEX idx_salary_payments_period      ON salary_payments(period_start, period_end);
CREATE INDEX idx_salary_payments_status      ON salary_payments(status);
