ALTER TABLE payment_attempts
    ADD COLUMN IF NOT EXISTS provider_txn_ref VARCHAR(80),
    ADD COLUMN IF NOT EXISTS provider_order_info VARCHAR(255),
    ADD COLUMN IF NOT EXISTS provider_client_ip VARCHAR(45);

-- Historical attempts keep provider_txn_ref NULL, so old duplicate order-number
-- references remain valid while every newly issued VNPAY session is unique.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_attempts_vnpay_session_ref
    ON payment_attempts(provider_txn_ref)
    WHERE provider = 'VNPAY' AND provider_txn_ref IS NOT NULL;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS attempt_id UUID REFERENCES payment_attempts(id);
CREATE INDEX IF NOT EXISTS idx_payments_attempt ON payments(attempt_id) WHERE attempt_id IS NOT NULL;
