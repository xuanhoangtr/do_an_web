CREATE INDEX IF NOT EXISTS idx_auth_sessions_cleanup_terminal_at
    ON auth_sessions ((COALESCE(revoked_at, expires_at)));

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_cleanup_terminal_at
    ON password_reset_tokens ((COALESCE(
        used_at,
        GREATEST(expires_at, COALESCE(otp_expires_at, expires_at))
    )));

CREATE INDEX IF NOT EXISTS idx_email_verification_challenges_cleanup_terminal_at
    ON email_verification_challenges ((COALESCE(consumed_at, expires_at)));
