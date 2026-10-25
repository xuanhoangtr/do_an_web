-- P2 immutable weekly settlement periods, holds and maker-checker payouts.
ALTER TABLE vendor_settlement_entries ADD COLUMN IF NOT EXISTS reason_code VARCHAR(30) NOT NULL DEFAULT 'SALE';
ALTER TABLE vendor_settlement_entries ADD COLUMN IF NOT EXISTS source_reference VARCHAR(190);
ALTER TABLE vendor_settlement_entries ADD COLUMN IF NOT EXISTS settlement_period_id UUID;
ALTER TABLE vendor_settlement_entries ADD CONSTRAINT settlement_reason_check
    CHECK (reason_code IN ('SALE','RETURN','REFUND','DISPUTE','RTS','COD_VARIANCE','PAYOUT_REVERSAL','CARRY_FORWARD'));

CREATE TABLE settlement_periods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    period_start TIMESTAMPTZ(6) NOT NULL,
    period_end TIMESTAMPTZ(6) NOT NULL,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING','LOCKED','APPROVED','PROCESSING','PAID','FAILED','REVERSED')),
    gross_amount BIGINT NOT NULL DEFAULT 0,
    commission_amount BIGINT NOT NULL DEFAULT 0,
    adjustment_amount BIGINT NOT NULL DEFAULT 0,
    net_amount BIGINT NOT NULL DEFAULT 0,
    payable_amount BIGINT NOT NULL DEFAULT 0 CHECK (payable_amount >= 0),
    carry_forward_amount BIGINT NOT NULL DEFAULT 0 CHECK (carry_forward_amount <= 0),
    approved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ(6),
    locked_at TIMESTAMPTZ(6),
    processed_at TIMESTAMPTZ(6),
    paid_at TIMESTAMPTZ(6),
    reversed_at TIMESTAMPTZ(6),
    reversal_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT settlement_period_range_check CHECK (period_start < period_end),
    UNIQUE (vendor_id, period_start, period_end)
);
CREATE INDEX idx_settlement_period_vendor_status ON settlement_periods(vendor_id, status, period_end DESC);
CREATE INDEX idx_settlement_period_work_queue ON settlement_periods(status, period_end, id)
    WHERE status IN ('PENDING','LOCKED','APPROVED','PROCESSING','FAILED');

ALTER TABLE vendor_settlement_entries ADD CONSTRAINT fk_settlement_entry_period
    FOREIGN KEY (settlement_period_id) REFERENCES settlement_periods(id) ON DELETE RESTRICT;
CREATE INDEX idx_settlement_entries_period ON vendor_settlement_entries(settlement_period_id)
    WHERE settlement_period_id IS NOT NULL;

CREATE TABLE finance_holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    settlement_entry_id UUID REFERENCES vendor_settlement_entries(id) ON DELETE RESTRICT,
    settlement_period_id UUID REFERENCES settlement_periods(id) ON DELETE RESTRICT,
    return_dispute_id UUID REFERENCES return_disputes(id) ON DELETE RESTRICT,
    reason_code VARCHAR(40) NOT NULL,
    note VARCHAR(1000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    released_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMPTZ(6),
    CONSTRAINT finance_hold_target_check CHECK (
        settlement_entry_id IS NOT NULL OR settlement_period_id IS NOT NULL OR return_dispute_id IS NOT NULL)
);
CREATE INDEX idx_finance_holds_vendor_active ON finance_holds(vendor_id, created_at DESC) WHERE active;
CREATE INDEX idx_finance_holds_period_active ON finance_holds(settlement_period_id) WHERE active;
CREATE INDEX idx_finance_holds_dispute_active ON finance_holds(return_dispute_id) WHERE active;

CREATE TABLE vendor_payout_destinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    method VARCHAR(30) NOT NULL DEFAULT 'MANUAL_BANK' CHECK (method IN ('MANUAL_BANK')),
    bank_code VARCHAR(40) NOT NULL,
    account_number VARCHAR(4096) NOT NULL,
    account_name VARCHAR(4096) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION' CHECK (status IN (
        'PENDING_VERIFICATION','VERIFIED','DISABLED')),
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_vendor_verified_payout_destination ON vendor_payout_destinations(vendor_id)
    WHERE status='VERIFIED';
CREATE INDEX idx_payout_destination_vendor ON vendor_payout_destinations(vendor_id, created_at DESC);
CREATE INDEX idx_payout_destination_verifier ON vendor_payout_destinations(verified_by) WHERE verified_by IS NOT NULL;

CREATE TABLE vendor_payouts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    settlement_period_id UUID NOT NULL UNIQUE REFERENCES settlement_periods(id) ON DELETE RESTRICT,
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    method VARCHAR(30) NOT NULL DEFAULT 'MANUAL_BANK' CHECK (method IN ('MANUAL_BANK')),
    destination_snapshot JSONB NOT NULL,
    provider_code VARCHAR(40) NOT NULL DEFAULT 'MANUAL',
    provider_reference VARCHAR(190),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING','PROCESSING','PAID','FAILED','RECONCILIATION_REQUIRED','REVERSED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    approved_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    processed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    note VARCHAR(1000),
    proof_media_id UUID REFERENCES managed_media_objects(id) ON DELETE RESTRICT,
    processed_at TIMESTAMPTZ(6),
    paid_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT payout_maker_checker CHECK (processed_by IS NULL OR processed_by <> approved_by)
);
CREATE INDEX idx_vendor_payouts_vendor_status ON vendor_payouts(vendor_id, status, created_at DESC);
CREATE INDEX idx_vendor_payouts_work_queue ON vendor_payouts(status, updated_at, id)
    WHERE status IN ('PENDING','PROCESSING','FAILED','RECONCILIATION_REQUIRED');
CREATE INDEX idx_vendor_payouts_proof_media ON vendor_payouts(proof_media_id) WHERE proof_media_id IS NOT NULL;

CREATE TABLE payout_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id UUID REFERENCES vendor_payouts(id) ON DELETE RESTRICT,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    operation VARCHAR(30) NOT NULL CHECK (operation IN ('APPROVE','PROCESS','MARK_PAID','REVERSE','RECONCILE')),
    idempotency_key VARCHAR(190) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','AMBIGUOUS')),
    response_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_payout_operations_payout ON payout_operations(payout_id, created_at DESC);
CREATE INDEX idx_payout_operations_actor ON payout_operations(actor_id, created_at DESC);

CREATE OR REPLACE FUNCTION protect_settlement_entry_money()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.fulfillment_id IS DISTINCT FROM OLD.fulfillment_id
       OR NEW.order_id IS DISTINCT FROM OLD.order_id
       OR NEW.vendor_id IS DISTINCT FROM OLD.vendor_id
       OR NEW.entry_type IS DISTINCT FROM OLD.entry_type
       OR NEW.gross_amount IS DISTINCT FROM OLD.gross_amount
       OR NEW.commission_amount IS DISTINCT FROM OLD.commission_amount
       OR NEW.vendor_net IS DISTINCT FROM OLD.vendor_net
       OR NEW.adjusts_entry_id IS DISTINCT FROM OLD.adjusts_entry_id
       OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
       OR NEW.source_reference IS DISTINCT FROM OLD.source_reference THEN
        RAISE EXCEPTION 'settlement money rows are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_settlement_entry_money_immutable
    BEFORE UPDATE ON vendor_settlement_entries FOR EACH ROW EXECUTE FUNCTION protect_settlement_entry_money();
CREATE TRIGGER trg_settlement_entries_no_delete
    BEFORE DELETE ON vendor_settlement_entries FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();
CREATE TRIGGER trg_payout_operations_append_only
    BEFORE UPDATE OR DELETE ON payout_operations FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

