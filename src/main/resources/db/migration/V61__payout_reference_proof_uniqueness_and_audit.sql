DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM vendor_payouts
        WHERE provider_reference IS NOT NULL
        GROUP BY provider_code, provider_reference HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate payout provider references exist; reconcile them before V61';
    END IF;
    IF EXISTS (
        SELECT 1 FROM vendor_payouts
        WHERE proof_media_id IS NOT NULL
        GROUP BY proof_media_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate payout proof media references exist; reconcile them before V61';
    END IF;
END $$;

CREATE UNIQUE INDEX uq_vendor_payout_provider_reference
    ON vendor_payouts(provider_code, provider_reference)
    WHERE provider_reference IS NOT NULL;
CREATE UNIQUE INDEX uq_vendor_payout_proof_media
    ON vendor_payouts(proof_media_id)
    WHERE proof_media_id IS NOT NULL;

ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PAYOUT_DESTINATION_VIEWED';
