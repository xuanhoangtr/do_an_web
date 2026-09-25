-- One-time email verification challenges keep only SHA-256 digests.
CREATE TABLE IF NOT EXISTS email_verification_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    otp_hash VARCHAR(64) NOT NULL,
    link_token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ(6) NOT NULL,
    last_sent_at TIMESTAMPTZ(6) NOT NULL,
    window_started_at TIMESTAMPTZ(6) NOT NULL,
    sent_count INTEGER NOT NULL DEFAULT 1,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    request_ip VARCHAR(64),
    consumed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_verify_email_created
    ON email_verification_challenges (email, created_at);
CREATE INDEX IF NOT EXISTS idx_email_verify_user_consumed
    ON email_verification_challenges (user_id, consumed_at);

ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS otp_hash VARCHAR(64);
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS otp_expires_at TIMESTAMPTZ(6);
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS otp_attempts INTEGER NOT NULL DEFAULT 0;
