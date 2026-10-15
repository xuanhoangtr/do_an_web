ALTER TABLE payment_provider_transactions
    DROP CONSTRAINT IF EXISTS payment_provider_transactions_status_check;

ALTER TABLE payment_provider_transactions
    ADD CONSTRAINT payment_provider_transactions_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX IF NOT EXISTS idx_provider_refund_intents_due
    ON payment_provider_transactions(status, processed_at, created_at)
    WHERE transaction_type = 'REFUND' AND status IN ('PENDING', 'PROCESSING');
