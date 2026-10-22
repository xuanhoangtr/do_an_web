ALTER TABLE return_cases ADD COLUMN IF NOT EXISTS return_required BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE return_case_items DROP CONSTRAINT IF EXISTS return_case_items_quantity_check;
ALTER TABLE return_case_items ADD CONSTRAINT return_case_items_quantity_check
    CHECK (approved_quantity <= requested_quantity
        AND received_quantity <= approved_quantity
        AND resolved_quantity <= approved_quantity);

ALTER TABLE return_evidence DROP CONSTRAINT IF EXISTS return_evidence_mime_check;
ALTER TABLE return_evidence ADD CONSTRAINT return_evidence_mime_check
    CHECK (mime_type IN ('image/jpeg','image/png'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_return_case_one_resolution
    ON return_resolutions(return_case_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_case_one_open_dispute
    ON return_disputes(return_case_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_case_shipment_direction
    ON return_shipments(return_case_id, direction);
CREATE INDEX IF NOT EXISTS idx_return_cases_reviewer_status
    ON return_cases(reviewed_by, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_return_resolutions_payment_transaction
    ON return_resolutions(payment_transaction_id) WHERE payment_transaction_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_return_disputes_decision_actor
    ON return_disputes(decision_actor, status) WHERE decision_actor IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_return_resolutions_payment_transaction') THEN
        ALTER TABLE return_resolutions ADD CONSTRAINT fk_return_resolutions_payment_transaction
            FOREIGN KEY (payment_transaction_id) REFERENCES payment_provider_transactions(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION prevent_return_case_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'return_case_history is append-only';
END $$;
DROP TRIGGER IF EXISTS trg_return_case_history_append_only ON return_case_history;
CREATE TRIGGER trg_return_case_history_append_only
    BEFORE UPDATE OR DELETE ON return_case_history
    FOR EACH ROW EXECUTE FUNCTION prevent_return_case_history_mutation();

ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_REQUESTED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_REVIEWED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_SHIPMENT_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_INSPECTED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_EVIDENCE_ADDED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_REFUND_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RETURN_CASE_DISPUTED';