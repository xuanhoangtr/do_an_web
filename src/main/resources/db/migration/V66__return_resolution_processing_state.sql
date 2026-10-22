ALTER TABLE return_resolutions
    DROP CONSTRAINT IF EXISTS return_resolutions_status_check;

ALTER TABLE return_resolutions
    ADD CONSTRAINT return_resolutions_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'MANUAL_REFUND_PENDING'));
