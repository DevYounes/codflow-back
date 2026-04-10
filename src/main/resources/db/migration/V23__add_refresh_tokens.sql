-- V23: Refresh tokens pour la gestion de session avec expiration par inactivité.
-- Chaque login génère un refresh token unique stocké ici.
-- last_used_at est mis à jour à chaque refresh — si inactif trop longtemps, la session expire.
-- Le token est supprimé au logout ou après expiration absolue (7 jours).
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(512) NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    last_used_at TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_token   ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);
