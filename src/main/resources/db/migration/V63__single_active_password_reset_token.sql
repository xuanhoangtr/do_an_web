-- Retain only the most recently created challenge for each user before enforcing rotation.
WITH active_tokens AS (
    SELECT id,
           row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC) AS position
    FROM password_reset_tokens
    WHERE used_at IS NULL
)
UPDATE password_reset_tokens token
SET used_at = CURRENT_TIMESTAMP
FROM active_tokens active
WHERE token.id = active.id
  AND active.position > 1;

CREATE UNIQUE INDEX uq_password_reset_tokens_single_active_user
    ON password_reset_tokens (user_id)
    WHERE used_at IS NULL;
