-- Vendor eligibility uses the return-window policy captured with the immutable
-- checkout finance snapshot. Existing V101 snapshots retain the current 7-day rule.
ALTER TABLE shop_order_financial_snapshots
    ADD COLUMN return_window_days_snapshot SMALLINT NOT NULL DEFAULT 7;
ALTER TABLE shop_order_financial_snapshots
    ADD CONSTRAINT ck_shop_financial_snapshot_return_window
        CHECK (return_window_days_snapshot BETWEEN 1 AND 365);

ALTER TABLE shop_settlement_entries
    ADD CONSTRAINT uq_shop_settlement_entry_id_shop UNIQUE (id, shop_id);

CREATE INDEX idx_shop_settlement_entries_assignment_scan
    ON shop_settlement_entries(shop_id, id) WHERE amount > 0;
CREATE INDEX idx_shop_settlement_fulfillment_eligibility
    ON order_fulfillments(shop_id, order_id, status, delivered_at);
CREATE INDEX idx_shop_settlement_return_eligibility
    ON return_cases(shop_id, order_id, status);

CREATE TABLE shop_settlement_periods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    period_start_at TIMESTAMPTZ(6) NOT NULL,
    period_end_at TIMESTAMPTZ(6) NOT NULL,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh'
        CHECK (timezone = 'Asia/Ho_Chi_Minh'),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED', 'PROCESSING', 'PAID')),
    credit_amount BIGINT NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),
    debit_amount BIGINT NOT NULL DEFAULT 0 CHECK (debit_amount >= 0),
    net_amount BIGINT NOT NULL DEFAULT 0,
    entry_count INTEGER NOT NULL DEFAULT 0 CHECK (entry_count >= 0),
    approved_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ(6),
    processing_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    processing_at TIMESTAMPTZ(6),
    paid_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    paid_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shop_settlement_period_range CHECK (
        period_start_at < period_end_at
        AND period_end_at = period_start_at + INTERVAL '168 hours'
        AND EXTRACT(ISODOW FROM period_start_at AT TIME ZONE 'Asia/Ho_Chi_Minh') = 1
        AND (period_start_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::TIME = TIME '00:00:00'
    ),
    CONSTRAINT ck_shop_settlement_period_net CHECK (
        net_amount = credit_amount - debit_amount
    ),
    CONSTRAINT ck_shop_settlement_period_actor_metadata CHECK (
        (approved_by IS NULL) = (approved_at IS NULL)
        AND (processing_by IS NULL) = (processing_at IS NULL)
        AND (paid_by IS NULL) = (paid_at IS NULL)
        AND (status NOT IN ('APPROVED', 'PROCESSING', 'PAID') OR approved_at IS NOT NULL)
        AND (status NOT IN ('PROCESSING', 'PAID') OR processing_at IS NOT NULL)
        AND (status <> 'PAID' OR paid_at IS NOT NULL)
    ),
    CONSTRAINT uq_shop_settlement_period_range UNIQUE (shop_id, period_start_at, period_end_at),
    CONSTRAINT uq_shop_settlement_period_id_shop UNIQUE (id, shop_id)
);

CREATE INDEX idx_shop_settlement_periods_status
    ON shop_settlement_periods(status, period_end_at, id);
CREATE INDEX idx_shop_settlement_periods_shop
    ON shop_settlement_periods(shop_id, period_start_at);

CREATE TABLE shop_settlement_period_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    settlement_entry_id UUID NOT NULL,
    entry_amount BIGINT NOT NULL CHECK (entry_amount > 0),
    entry_direction VARCHAR(8) NOT NULL CHECK (entry_direction IN ('CREDIT', 'DEBIT')),
    entry_type VARCHAR(20) NOT NULL CHECK (entry_type IN ('SALE', 'REFUND', 'ADJUSTMENT', 'RECOVERY')),
    source_identity VARCHAR(190) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_settlement_period_entry_period_shop
        FOREIGN KEY (period_id, shop_id)
        REFERENCES shop_settlement_periods(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_settlement_period_entry_source_shop
        FOREIGN KEY (settlement_entry_id, shop_id)
        REFERENCES shop_settlement_entries(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT uq_shop_settlement_period_entry_source UNIQUE (settlement_entry_id)
);

CREATE INDEX idx_shop_settlement_period_entries_period
    ON shop_settlement_period_entries(period_id, created_at, id);

CREATE OR REPLACE FUNCTION guard_shop_settlement_period_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.period_start_at IS DISTINCT FROM OLD.period_start_at
       OR NEW.period_end_at IS DISTINCT FROM OLD.period_end_at
       OR NEW.timezone IS DISTINCT FROM OLD.timezone
       OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'Settlement period identity and range are immutable';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'APPROVED')
        OR (OLD.status = 'APPROVED' AND NEW.status = 'PROCESSING')
        OR (OLD.status = 'PROCESSING' AND NEW.status = 'PAID')
    ) THEN
        RAISE EXCEPTION 'Invalid settlement period state transition';
    END IF;

    IF (NEW.credit_amount IS DISTINCT FROM OLD.credit_amount
        OR NEW.debit_amount IS DISTINCT FROM OLD.debit_amount
        OR NEW.net_amount IS DISTINCT FROM OLD.net_amount
        OR NEW.entry_count IS DISTINCT FROM OLD.entry_count)
       AND (OLD.status <> 'DRAFT' OR NEW.status <> 'DRAFT') THEN
        RAISE EXCEPTION 'Only draft settlement period totals can change';
    END IF;

    IF (OLD.approved_at IS NOT NULL AND
        (NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.approved_by IS DISTINCT FROM OLD.approved_by))
       OR (OLD.processing_at IS NOT NULL AND
        (NEW.processing_at IS DISTINCT FROM OLD.processing_at OR NEW.processing_by IS DISTINCT FROM OLD.processing_by))
       OR (OLD.paid_at IS NOT NULL AND
        (NEW.paid_at IS DISTINCT FROM OLD.paid_at OR NEW.paid_by IS DISTINCT FROM OLD.paid_by)) THEN
        RAISE EXCEPTION 'Settlement period actor evidence is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_settlement_periods_state_guard
    BEFORE UPDATE ON shop_settlement_periods
    FOR EACH ROW EXECUTE FUNCTION guard_shop_settlement_period_update();

CREATE OR REPLACE FUNCTION prevent_shop_settlement_period_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Settlement periods are retained as financial history';
END $$;

CREATE TRIGGER trg_shop_settlement_periods_no_delete
    BEFORE DELETE ON shop_settlement_periods
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_settlement_period_delete();

CREATE OR REPLACE FUNCTION ensure_shop_settlement_period_is_draft()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM shop_settlement_periods p
        WHERE p.id = NEW.period_id AND p.shop_id = NEW.shop_id AND p.status = 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'Entries can only be assigned to a draft settlement period';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_settlement_period_entry_draft_guard
    BEFORE INSERT ON shop_settlement_period_entries
    FOR EACH ROW EXECUTE FUNCTION ensure_shop_settlement_period_is_draft();

CREATE OR REPLACE FUNCTION prevent_shop_settlement_period_entry_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Settlement period assignments are immutable';
END $$;

CREATE TRIGGER trg_shop_settlement_period_entries_append_only
    BEFORE UPDATE OR DELETE ON shop_settlement_period_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_settlement_period_entry_mutation();
