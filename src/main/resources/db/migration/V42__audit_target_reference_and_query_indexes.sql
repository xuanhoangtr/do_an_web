ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS target_ref VARCHAR(80);

UPDATE audit_logs
SET target_ref = target_id::TEXT
WHERE target_ref IS NULL AND target_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_logs_target_ref_created_at
    ON audit_logs (target_type, target_ref, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at
    ON audit_logs (created_at DESC);
