-- The canonical review body is stored in content. Keep the legacy comment
-- column for compatibility, but allow new JPA writes to omit it.
ALTER TABLE product_reviews
    ALTER COLUMN comment DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires_at
    ON auth_sessions (expires_at);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_revoked_at
    ON auth_sessions (revoked_at)
    WHERE revoked_at IS NOT NULL;
