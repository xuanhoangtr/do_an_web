CREATE TABLE IF NOT EXISTS web_session_metadata (
    session_id CHAR(36) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    login_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_web_session_metadata_user_activity
    ON web_session_metadata (user_id, last_activity_at DESC);
