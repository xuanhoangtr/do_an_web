-- Payment attempts, provider captures/refunds, callbacks, and reconciliation.
-- Order and payment status checks grow to cover refund lifecycle states.

ALTER TABLE payments ADD COLUMN IF NOT EXISTS refunded_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE orders ALTER COLUMN payment_status TYPE VARCHAR(32);

DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.payments') IS NOT NULL THEN
        FOR c IN
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'payments'::regclass
              AND contype = 'c'
              AND pg_get_constraintdef(oid) ILIKE '%status%'
        LOOP
            EXECUTE format('ALTER TABLE payments DROP CONSTRAINT IF EXISTS %I', c);
        END LOOP;
        ALTER TABLE payments ADD CONSTRAINT payments_status_check
            CHECK (status IN (
                'PENDING','PROCESSING','SUCCEEDED','FAILED',
                'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED','REFUND_FAILED'
            ));
    END IF;
END $$;

DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.orders') IS NOT NULL THEN
        FOR c IN
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'orders'::regclass
              AND contype = 'c'
              AND pg_get_constraintdef(oid) ILIKE '%payment_status%'
        LOOP
            EXECUTE format('ALTER TABLE orders DROP CONSTRAINT IF EXISTS %I', c);
        END LOOP;
        ALTER TABLE orders ADD CONSTRAINT orders_payment_status_check
            CHECK (payment_status IN (
                'PENDING','PROCESSING','SUCCEEDED','FAILED',
                'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED','REFUND_FAILED'
            ));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS payment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    provider VARCHAR(60) NOT NULL,
    merchant_reference VARCHAR(80) NOT NULL,
    provider_request_id VARCHAR(190),
    provider_transaction_id VARCHAR(190),
    provider_create_date VARCHAR(14),
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','EXPIRED')),
    failure_code VARCHAR(40),
    failure_reason VARCHAR(500),
    expires_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payment_attempts_order ON payment_attempts(order_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_attempts_open ON payment_attempts(status, updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_attempts_provider_txn
    ON payment_attempts(provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS payment_provider_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES payment_attempts(id),
    payment_id UUID REFERENCES payments(id),
    provider VARCHAR(60) NOT NULL,
    provider_transaction_id VARCHAR(190),
    provider_request_id VARCHAR(190),
    transaction_type VARCHAR(20) NOT NULL CHECK (transaction_type IN ('CAPTURE','REFUND')),
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    idempotency_key VARCHAR(190) NOT NULL UNIQUE,
    response_code VARCHAR(40),
    response_message VARCHAR(500),
    raw_response VARCHAR(4000),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_provider_transactions_order
    ON payment_provider_transactions(order_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_capture
    ON payment_provider_transactions(provider, provider_transaction_id)
    WHERE transaction_type = 'CAPTURE' AND provider_transaction_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS payment_callbacks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(60) NOT NULL,
    order_id UUID REFERENCES orders(id) ON DELETE SET NULL,
    attempt_id UUID REFERENCES payment_attempts(id),
    provider_transaction_id VARCHAR(190),
    signature_valid BOOLEAN NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    response_code VARCHAR(40),
    transaction_status VARCHAR(40),
    amount BIGINT,
    payload_sha256 CHAR(64) NOT NULL,
    raw_payload VARCHAR(4000) NOT NULL,
    received_at TIMESTAMPTZ(6) NOT NULL,
    UNIQUE (provider, payload_sha256)
);
CREATE INDEX IF NOT EXISTS idx_payment_callbacks_order ON payment_callbacks(order_id, received_at DESC);

CREATE TABLE IF NOT EXISTS payment_reconciliations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES payment_attempts(id),
    provider VARCHAR(60) NOT NULL,
    merchant_reference VARCHAR(80) NOT NULL,
    local_status VARCHAR(30) NOT NULL,
    provider_status VARCHAR(30),
    provider_transaction_id VARCHAR(190),
    provider_amount BIGINT,
    reason VARCHAR(40) NOT NULL,
    resolution VARCHAR(30) NOT NULL,
    detail VARCHAR(500),
    checked_at TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_payment_reconciliations_order
    ON payment_reconciliations(order_id, checked_at DESC);
