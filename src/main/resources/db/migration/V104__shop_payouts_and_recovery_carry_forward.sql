-- VND-025 payout records and VND-024 recovery offsets share this migration so every
-- deployed schema gets one consistent payout/recovery contract after V103.

ALTER TABLE shop_settlement_periods
    ADD COLUMN recovery_debit_amount BIGINT NOT NULL DEFAULT 0
        CHECK (recovery_debit_amount >= 0),
    ADD COLUMN last_recalculation_id UUID;

ALTER TABLE shop_settlement_periods
    DROP CONSTRAINT ck_shop_settlement_period_net,
    ADD CONSTRAINT ck_shop_settlement_period_net
        CHECK (net_amount = credit_amount - debit_amount - recovery_debit_amount);

CREATE TABLE shop_payout_destinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL CHECK (version > 0),
    bank_code VARCHAR(40) NOT NULL CHECK (length(btrim(bank_code)) > 0),
    account_number VARCHAR(4096) NOT NULL CHECK (length(btrim(account_number)) > 0),
    account_holder_name VARCHAR(4096) NOT NULL CHECK (length(btrim(account_holder_name)) > 0),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'SUPERSEDED', 'DISABLED')),
    submitted_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    submitted_at TIMESTAMPTZ(6) NOT NULL,
    verified_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    verified_at TIMESTAMPTZ(6),
    verification_reference VARCHAR(190),
    verification_note VARCHAR(1000),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_payout_destination_version UNIQUE (shop_id, version),
    CONSTRAINT uq_shop_payout_destination_id_shop UNIQUE (id, shop_id),
    CONSTRAINT ck_shop_payout_destination_verification CHECK (
        (status = 'PENDING_VERIFICATION' AND verified_by IS NULL AND verified_at IS NULL)
        OR (status <> 'PENDING_VERIFICATION' AND verified_by IS NOT NULL AND verified_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_shop_payout_destination_verified
    ON shop_payout_destinations(shop_id) WHERE status = 'VERIFIED';
CREATE INDEX idx_shop_payout_destinations_status
    ON shop_payout_destinations(status, created_at, id);

CREATE OR REPLACE FUNCTION guard_shop_payout_destination_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.version IS DISTINCT FROM OLD.version
       OR NEW.bank_code IS DISTINCT FROM OLD.bank_code
       OR NEW.account_number IS DISTINCT FROM OLD.account_number
       OR NEW.account_holder_name IS DISTINCT FROM OLD.account_holder_name
       OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
       OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Payout destination identity and bank details are immutable';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'PENDING_VERIFICATION' AND NEW.status IN ('VERIFIED', 'SUPERSEDED', 'DISABLED'))
        OR (OLD.status = 'VERIFIED' AND NEW.status IN ('SUPERSEDED', 'DISABLED'))
    ) THEN
        RAISE EXCEPTION 'Invalid payout destination state transition';
    END IF;
    IF OLD.verified_at IS NOT NULL AND
       (NEW.verified_at IS DISTINCT FROM OLD.verified_at
        OR NEW.verified_by IS DISTINCT FROM OLD.verified_by
        OR NEW.verification_reference IS DISTINCT FROM OLD.verification_reference
        OR NEW.verification_note IS DISTINCT FROM OLD.verification_note) THEN
        RAISE EXCEPTION 'Payout destination verification evidence is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_payout_destinations_state_guard
    BEFORE UPDATE ON shop_payout_destinations
    FOR EACH ROW EXECUTE FUNCTION guard_shop_payout_destination_update();

CREATE TRIGGER trg_shop_payout_destinations_no_delete
    BEFORE DELETE ON shop_payout_destinations
    FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

CREATE TABLE shop_payouts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    settlement_period_id UUID NOT NULL,
    destination_id UUID NOT NULL,
    destination_version BIGINT NOT NULL CHECK (destination_version > 0),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    destination_bank_code_snapshot VARCHAR(40) NOT NULL,
    destination_account_number_snapshot VARCHAR(4096) NOT NULL,
    destination_account_holder_snapshot VARCHAR(4096) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'APPROVED', 'PROCESSING', 'PAID', 'CANCELLED')),
    requested_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    requested_at TIMESTAMPTZ(6) NOT NULL,
    approved_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ(6),
    processing_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    processing_at TIMESTAMPTZ(6),
    paid_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    paid_at TIMESTAMPTZ(6),
    provider_code VARCHAR(40) NOT NULL DEFAULT 'MANUAL_BANK',
    provider_reference VARCHAR(190),
    proof_media_id UUID,
    note VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_payout_id_shop UNIQUE (id, shop_id),
    CONSTRAINT fk_shop_payout_period_shop
        FOREIGN KEY (settlement_period_id, shop_id)
        REFERENCES shop_settlement_periods(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_payout_destination_shop
        FOREIGN KEY (destination_id, shop_id)
        REFERENCES shop_payout_destinations(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_payout_actor_metadata CHECK (
        (approved_by IS NULL) = (approved_at IS NULL)
        AND (processing_by IS NULL) = (processing_at IS NULL)
        AND (paid_by IS NULL) = (paid_at IS NULL)
        AND (status NOT IN ('APPROVED', 'PROCESSING', 'PAID') OR approved_at IS NOT NULL)
        AND (status NOT IN ('PROCESSING', 'PAID') OR processing_at IS NOT NULL)
        AND (status <> 'PAID' OR (paid_at IS NOT NULL AND provider_reference IS NOT NULL AND proof_media_id IS NOT NULL))
    )
);

CREATE INDEX idx_shop_payouts_shop_status
    ON shop_payouts(shop_id, status, created_at);
CREATE UNIQUE INDEX uq_shop_payout_active_period
    ON shop_payouts(settlement_period_id) WHERE status <> 'CANCELLED';
CREATE INDEX idx_shop_payouts_queue
    ON shop_payouts(status, updated_at, id);
CREATE UNIQUE INDEX uq_shop_payout_provider_reference
    ON shop_payouts(provider_code, lower(provider_reference)) WHERE provider_reference IS NOT NULL;
CREATE UNIQUE INDEX uq_shop_payout_proof_media
    ON shop_payouts(proof_media_id) WHERE proof_media_id IS NOT NULL;

CREATE OR REPLACE FUNCTION guard_shop_payout_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.settlement_period_id IS DISTINCT FROM OLD.settlement_period_id
       OR NEW.destination_id IS DISTINCT FROM OLD.destination_id
       OR NEW.destination_version IS DISTINCT FROM OLD.destination_version
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.destination_bank_code_snapshot IS DISTINCT FROM OLD.destination_bank_code_snapshot
       OR NEW.destination_account_number_snapshot IS DISTINCT FROM OLD.destination_account_number_snapshot
       OR NEW.destination_account_holder_snapshot IS DISTINCT FROM OLD.destination_account_holder_snapshot
       OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
       OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
       OR NEW.provider_code IS DISTINCT FROM OLD.provider_code
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Payout identity, amount, and destination snapshot are immutable';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'REQUESTED' AND NEW.status IN ('APPROVED', 'CANCELLED'))
        OR (OLD.status = 'APPROVED' AND NEW.status IN ('PROCESSING', 'CANCELLED'))
        OR (OLD.status = 'PROCESSING' AND NEW.status = 'PAID')
    ) THEN
        RAISE EXCEPTION 'Invalid payout state transition';
    END IF;
    IF (OLD.approved_at IS NOT NULL AND
        (NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.approved_by IS DISTINCT FROM OLD.approved_by))
       OR (OLD.processing_at IS NOT NULL AND
        (NEW.processing_at IS DISTINCT FROM OLD.processing_at OR NEW.processing_by IS DISTINCT FROM OLD.processing_by))
       OR (OLD.paid_at IS NOT NULL AND
        (NEW.paid_at IS DISTINCT FROM OLD.paid_at OR NEW.paid_by IS DISTINCT FROM OLD.paid_by
         OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
         OR NEW.proof_media_id IS DISTINCT FROM OLD.proof_media_id)) THEN
        RAISE EXCEPTION 'Payout actor and transfer evidence are immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_payouts_state_guard
    BEFORE UPDATE ON shop_payouts
    FOR EACH ROW EXECUTE FUNCTION guard_shop_payout_update();

CREATE TRIGGER trg_shop_payouts_no_delete
    BEFORE DELETE ON shop_payouts
    FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

CREATE OR REPLACE FUNCTION validate_shop_payout_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM shop_settlement_periods period
        WHERE period.id = NEW.settlement_period_id AND period.shop_id = NEW.shop_id
          AND period.status = 'APPROVED' AND period.net_amount = NEW.amount
          AND period.net_amount > 0
    ) THEN
        RAISE EXCEPTION 'Payout must snapshot an approved positive settlement period amount';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM shop_payout_destinations destination
        WHERE destination.id = NEW.destination_id AND destination.shop_id = NEW.shop_id
          AND destination.version = NEW.destination_version AND destination.status = 'VERIFIED'
          AND destination.bank_code = NEW.destination_bank_code_snapshot
    ) THEN
        RAISE EXCEPTION 'Payout must snapshot the verified payout destination';
    END IF;
    IF length(btrim(NEW.destination_account_number_snapshot)) = 0
       OR length(btrim(NEW.destination_account_holder_snapshot)) = 0 THEN
        RAISE EXCEPTION 'Payout destination account snapshots cannot be blank';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_payouts_insert_integrity
    BEFORE INSERT ON shop_payouts
    FOR EACH ROW EXECUTE FUNCTION validate_shop_payout_insert();

CREATE TABLE shop_payout_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    operation_type VARCHAR(30) NOT NULL CHECK (operation_type IN (
        'SUBMIT_DESTINATION', 'VERIFY_DESTINATION', 'REQUEST_PAYOUT', 'APPROVE_PAYOUT',
        'PROCESS_PAYOUT', 'MARK_PAYOUT_PAID', 'CANCEL_PAYOUT')),
    idempotency_key VARCHAR(190) NOT NULL CHECK (length(btrim(idempotency_key)) > 0),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    destination_id UUID REFERENCES shop_payout_destinations(id) ON DELETE RESTRICT,
    payout_id UUID REFERENCES shop_payouts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_payout_operation_actor_key UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_shop_payout_operation_target CHECK (
        ((destination_id IS NOT NULL) <> (payout_id IS NOT NULL))
        AND ((operation_type IN ('SUBMIT_DESTINATION', 'VERIFY_DESTINATION')) = (destination_id IS NOT NULL))
    )
);

CREATE INDEX idx_shop_payout_operations_payout
    ON shop_payout_operations(payout_id, created_at, id) WHERE payout_id IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_shop_payout_operation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Payout operation idempotency records are append-only';
END $$;

CREATE TRIGGER trg_shop_payout_operations_append_only
    BEFORE UPDATE OR DELETE ON shop_payout_operations
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_payout_operation_mutation();

CREATE TABLE shop_payout_recovery_debts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    payout_id UUID NOT NULL,
    refund_entry_id UUID NOT NULL UNIQUE,
    amount BIGINT NOT NULL CHECK (amount > 0),
    outstanding_amount BIGINT NOT NULL CHECK (outstanding_amount >= 0 AND outstanding_amount <= amount),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_payout_recovery_debt_id_shop UNIQUE (id, shop_id),
    CONSTRAINT fk_shop_payout_recovery_payout_shop
        FOREIGN KEY (payout_id, shop_id) REFERENCES shop_payouts(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_payout_recovery_refund_shop
        FOREIGN KEY (refund_entry_id, shop_id) REFERENCES shop_settlement_entries(id, shop_id) ON DELETE RESTRICT
);

CREATE INDEX idx_shop_payout_recovery_debts_outstanding
    ON shop_payout_recovery_debts(shop_id, created_at, id) WHERE outstanding_amount > 0;

CREATE OR REPLACE FUNCTION validate_shop_payout_recovery_debt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    offset_total BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.outstanding_amount <> NEW.amount THEN
            RAISE EXCEPTION 'New recovery debt must begin with its full outstanding amount';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM shop_settlement_entries refund
            JOIN shop_payouts payout ON payout.id = NEW.payout_id AND payout.shop_id = NEW.shop_id
            JOIN shop_settlement_period_entries sale_assignment
              ON sale_assignment.period_id = payout.settlement_period_id
             AND sale_assignment.shop_id = NEW.shop_id
             AND sale_assignment.entry_type = 'SALE'
            JOIN shop_settlement_entries sale ON sale.id = sale_assignment.settlement_entry_id
            WHERE refund.id = NEW.refund_entry_id AND refund.shop_id = NEW.shop_id
              AND refund.entry_type = 'REFUND' AND refund.direction = 'DEBIT'
              AND refund.amount = NEW.amount AND payout.status IN ('PROCESSING', 'PAID')
              AND sale.order_id IS NOT NULL AND sale.order_id = refund.order_id
        ) THEN
            RAISE EXCEPTION 'Recovery debt must link a refund to a sale included in a processing or paid payout';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.payout_id IS DISTINCT FROM OLD.payout_id
       OR NEW.refund_entry_id IS DISTINCT FROM OLD.refund_entry_id
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Recovery debt identity and amount are immutable';
    END IF;
    IF NEW.outstanding_amount > OLD.outstanding_amount THEN
        RAISE EXCEPTION 'Recovery debt outstanding amount may only decrease';
    END IF;
    SELECT coalesce(sum(applied.amount), 0) INTO offset_total
    FROM shop_settlement_recovery_offsets applied
    WHERE applied.recovery_debt_id = NEW.id AND applied.shop_id = NEW.shop_id
      AND applied.status = 'APPLIED';
    IF NEW.outstanding_amount <> NEW.amount - offset_total THEN
        RAISE EXCEPTION 'Recovery debt outstanding amount must equal its unapplied balance';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_payout_recovery_debts_integrity
    BEFORE INSERT OR UPDATE ON shop_payout_recovery_debts
    FOR EACH ROW EXECUTE FUNCTION validate_shop_payout_recovery_debt();

CREATE TRIGGER trg_shop_payout_recovery_debts_no_delete
    BEFORE DELETE ON shop_payout_recovery_debts
    FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

CREATE TABLE shop_settlement_recovery_offsets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    recovery_debt_id UUID NOT NULL,
    period_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'RESERVED'
        CHECK (status IN ('RESERVED', 'APPLIED', 'RELEASED')),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_settlement_recovery_offset_scope UNIQUE (id, shop_id, period_id, recovery_debt_id),
    CONSTRAINT fk_shop_settlement_recovery_offset_debt_shop
        FOREIGN KEY (recovery_debt_id, shop_id)
        REFERENCES shop_payout_recovery_debts(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_settlement_recovery_offset_period_shop
        FOREIGN KEY (period_id, shop_id)
        REFERENCES shop_settlement_periods(id, shop_id) ON DELETE RESTRICT
);

CREATE INDEX idx_shop_settlement_recovery_offsets_period
    ON shop_settlement_recovery_offsets(period_id, created_at, id);
CREATE UNIQUE INDEX uq_shop_settlement_recovery_active_debt_period
    ON shop_settlement_recovery_offsets(recovery_debt_id, period_id) WHERE status = 'RESERVED';

CREATE TABLE shop_settlement_recovery_offset_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    offset_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    period_id UUID NOT NULL,
    recovery_debt_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_settlement_recovery_offset_event_scope
        FOREIGN KEY (offset_id, shop_id, period_id, recovery_debt_id)
        REFERENCES shop_settlement_recovery_offsets(id, shop_id, period_id, recovery_debt_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_shop_settlement_recovery_offset_event_transition CHECK (
        (from_status IS NULL AND to_status = 'RESERVED')
        OR (from_status = 'RESERVED' AND to_status IN ('APPLIED', 'RELEASED'))
    )
);

CREATE INDEX idx_shop_settlement_recovery_offset_events_offset
    ON shop_settlement_recovery_offset_events(offset_id, created_at, id);

CREATE OR REPLACE FUNCTION record_shop_settlement_recovery_offset_event()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO shop_settlement_recovery_offset_events(
        offset_id, shop_id, period_id, recovery_debt_id, amount, from_status, to_status)
    VALUES (NEW.id, NEW.shop_id, NEW.period_id, NEW.recovery_debt_id, NEW.amount,
        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END, NEW.status);
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_settlement_recovery_offset_event_insert
    AFTER INSERT ON shop_settlement_recovery_offsets
    FOR EACH ROW EXECUTE FUNCTION record_shop_settlement_recovery_offset_event();

CREATE TRIGGER trg_shop_settlement_recovery_offset_event_update
    AFTER UPDATE OF status ON shop_settlement_recovery_offsets
    FOR EACH ROW EXECUTE FUNCTION record_shop_settlement_recovery_offset_event();

CREATE OR REPLACE FUNCTION prevent_shop_settlement_recovery_offset_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Settlement recovery offset history is append-only';
END $$;

CREATE TRIGGER trg_shop_settlement_recovery_offset_events_append_only
    BEFORE UPDATE OR DELETE ON shop_settlement_recovery_offset_events
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_settlement_recovery_offset_event_mutation();

CREATE OR REPLACE FUNCTION validate_shop_settlement_recovery_offset()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    offset_total BIGINT;
    period_available BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RESERVED' OR NOT EXISTS (
            SELECT 1 FROM shop_settlement_periods period
            JOIN shop_payout_recovery_debts debt
              ON debt.id = NEW.recovery_debt_id AND debt.shop_id = NEW.shop_id
            JOIN shop_payouts payout ON payout.id = debt.payout_id AND payout.shop_id = debt.shop_id
            JOIN shop_settlement_periods source_period
              ON source_period.id = payout.settlement_period_id AND source_period.shop_id = payout.shop_id
            WHERE period.id = NEW.period_id AND period.shop_id = NEW.shop_id
              AND period.status = 'DRAFT'
              AND period.period_start_at > source_period.period_start_at
              AND debt.outstanding_amount >= NEW.amount
        ) THEN
            RAISE EXCEPTION 'Recovery offsets require available debt and a later draft settlement period';
        END IF;
        SELECT coalesce(sum(applied.amount), 0) INTO offset_total
        FROM shop_settlement_recovery_offsets applied
        WHERE applied.recovery_debt_id = NEW.recovery_debt_id AND applied.shop_id = NEW.shop_id
          AND applied.status = 'RESERVED';
        IF offset_total + NEW.amount > (SELECT debt.outstanding_amount FROM shop_payout_recovery_debts debt
                                        WHERE debt.id = NEW.recovery_debt_id AND debt.shop_id = NEW.shop_id) THEN
            RAISE EXCEPTION 'Reserved recovery offsets cannot exceed outstanding debt';
        END IF;
        SELECT period.credit_amount - period.debit_amount - coalesce(sum(applied.amount), 0)
          INTO period_available
        FROM shop_settlement_periods period
        LEFT JOIN shop_settlement_recovery_offsets applied
          ON applied.period_id = period.id AND applied.shop_id = period.shop_id
         AND applied.status IN ('RESERVED', 'APPLIED')
        WHERE period.id = NEW.period_id AND period.shop_id = NEW.shop_id
        GROUP BY period.id, period.credit_amount, period.debit_amount;
        IF period_available < NEW.amount THEN
            RAISE EXCEPTION 'Recovery offsets cannot exceed positive settlement period net';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.recovery_debt_id IS DISTINCT FROM OLD.recovery_debt_id
       OR NEW.period_id IS DISTINCT FROM OLD.period_id
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Settlement recovery offset identity and amount are immutable';
    END IF;
    IF OLD.status = 'RESERVED' AND NEW.status = 'RELEASED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM shop_settlement_periods period
            WHERE period.id = OLD.period_id AND period.shop_id = OLD.shop_id
              AND period.status = 'DRAFT'
              AND (
                  period.last_recalculation_id IS NOT NULL
                  OR EXISTS (
                      SELECT 1
                      FROM shop_settlement_periods recalculated_target
                      JOIN shop_settlement_period_recalculations recalculation
                        ON recalculation.id = recalculated_target.last_recalculation_id
                       AND recalculation.shop_id = recalculated_target.shop_id
                       AND recalculation.period_id = recalculated_target.id
                      WHERE recalculated_target.status = 'DRAFT'
                        AND recalculation.reason_code = 'POST_APPROVAL_RECOVERY'
                        AND recalculation.affected_recovery_debt_id = OLD.recovery_debt_id
                  )
              )
        ) THEN
            RAISE EXCEPTION 'Recovery reservations may be released only during audited recovery recalculation';
        END IF;
    ELSIF OLD.status = 'RESERVED' AND NEW.status = 'APPLIED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM shop_settlement_periods period
            WHERE period.id = OLD.period_id AND period.shop_id = OLD.shop_id AND period.status = 'PAID'
        ) THEN
            RAISE EXCEPTION 'Recovery reservations settle only after the period is paid';
        END IF;
    ELSE
        RAISE EXCEPTION 'Invalid recovery offset state transition';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_settlement_recovery_offsets_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON shop_settlement_recovery_offsets
    FOR EACH ROW EXECUTE FUNCTION validate_shop_settlement_recovery_offset();

CREATE OR REPLACE FUNCTION apply_shop_recovery_offsets_after_period_paid()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    settled_offset RECORD;
    updated_rows INTEGER;
BEGIN
    FOR settled_offset IN
        UPDATE shop_settlement_recovery_offsets
        SET status = 'APPLIED'
        WHERE period_id = NEW.id AND shop_id = NEW.shop_id AND status = 'RESERVED'
        RETURNING recovery_debt_id, shop_id, amount
    LOOP
        UPDATE shop_payout_recovery_debts
        SET outstanding_amount = outstanding_amount - settled_offset.amount,
            version = version + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = settled_offset.recovery_debt_id AND shop_id = settled_offset.shop_id
          AND outstanding_amount >= settled_offset.amount;
        GET DIAGNOSTICS updated_rows = ROW_COUNT;
        IF updated_rows <> 1 THEN
            RAISE EXCEPTION 'Recovery debt balance changed before settlement offset was paid';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_settlement_period_paid_recovery_offsets
    AFTER UPDATE OF status ON shop_settlement_periods
    FOR EACH ROW WHEN (OLD.status = 'PROCESSING' AND NEW.status = 'PAID')
    EXECUTE FUNCTION apply_shop_recovery_offsets_after_period_paid();

CREATE TABLE shop_settlement_period_recalculations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL,
    period_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    affected_debit_entry_id UUID,
    affected_recovery_debt_id UUID,
    prior_approved_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    prior_approved_at TIMESTAMPTZ(6) NOT NULL,
    prior_credit_amount BIGINT NOT NULL,
    prior_debit_amount BIGINT NOT NULL,
    prior_recovery_debit_amount BIGINT NOT NULL,
    prior_net_amount BIGINT NOT NULL,
    prior_entry_count INTEGER NOT NULL,
    reason_code VARCHAR(40) NOT NULL CHECK (
        reason_code IN ('POST_APPROVAL_DEBIT', 'POST_APPROVAL_RECOVERY')),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_settlement_period_recalculation_id_scope UNIQUE (id, shop_id, period_id),
    CONSTRAINT fk_shop_settlement_period_recalculation_period_shop
        FOREIGN KEY (period_id, shop_id) REFERENCES shop_settlement_periods(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_settlement_period_recalculation_debit_shop
        FOREIGN KEY (affected_debit_entry_id, shop_id)
        REFERENCES shop_settlement_entries(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_settlement_period_recalculation_recovery_shop
        FOREIGN KEY (affected_recovery_debt_id, shop_id)
        REFERENCES shop_payout_recovery_debts(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_settlement_period_recalculation_source CHECK (
        (reason_code = 'POST_APPROVAL_DEBIT'
         AND affected_debit_entry_id IS NOT NULL AND affected_recovery_debt_id IS NULL)
        OR (reason_code = 'POST_APPROVAL_RECOVERY'
            AND affected_debit_entry_id IS NULL AND affected_recovery_debt_id IS NOT NULL)
    )
);

ALTER TABLE shop_settlement_periods
    ADD CONSTRAINT fk_shop_settlement_period_last_recalculation
        FOREIGN KEY (last_recalculation_id, shop_id, id)
        REFERENCES shop_settlement_period_recalculations(id, shop_id, period_id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION prevent_shop_settlement_period_recalculation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Settlement period recalculation audit is append-only';
END $$;

CREATE TRIGGER trg_shop_settlement_period_recalculations_append_only
    BEFORE UPDATE OR DELETE ON shop_settlement_period_recalculations
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_settlement_period_recalculation_mutation();

CREATE OR REPLACE FUNCTION guard_shop_settlement_period_update()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    valid_recalculation BOOLEAN;
    reserved_recovery_amount BIGINT;
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.period_start_at IS DISTINCT FROM OLD.period_start_at
       OR NEW.period_end_at IS DISTINCT FROM OLD.period_end_at
       OR NEW.timezone IS DISTINCT FROM OLD.timezone
       OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'Settlement period identity and range are immutable';
    END IF;

    IF OLD.status = 'APPROVED' AND NEW.status = 'DRAFT' THEN
        SELECT EXISTS (
            SELECT 1 FROM shop_settlement_period_recalculations recalculation
            WHERE recalculation.id = NEW.last_recalculation_id
              AND recalculation.shop_id = OLD.shop_id
              AND recalculation.period_id = OLD.id
              AND recalculation.prior_approved_by = OLD.approved_by
              AND recalculation.prior_approved_at = OLD.approved_at
              AND recalculation.prior_credit_amount = OLD.credit_amount
              AND recalculation.prior_debit_amount = OLD.debit_amount
              AND recalculation.prior_recovery_debit_amount = OLD.recovery_debit_amount
              AND recalculation.prior_net_amount = OLD.net_amount
              AND recalculation.prior_entry_count = OLD.entry_count
              AND recalculation.created_at >= OLD.approved_at
              AND (
                  (recalculation.reason_code = 'POST_APPROVAL_DEBIT'
                   AND EXISTS (
                       SELECT 1 FROM shop_settlement_entries debit
                       WHERE debit.id = recalculation.affected_debit_entry_id
                         AND debit.shop_id = recalculation.shop_id
                         AND debit.entry_type IN ('REFUND', 'ADJUSTMENT')
                         AND debit.direction = 'DEBIT' AND debit.created_at >= OLD.approved_at
                         AND NOT EXISTS (
                             SELECT 1 FROM shop_settlement_period_entries prior_assignment
                             WHERE prior_assignment.settlement_entry_id = debit.id
                         )
                         AND EXISTS (
                             SELECT 1 FROM shop_settlement_period_entries sale_assignment
                             JOIN shop_settlement_entries sale
                               ON sale.id = sale_assignment.settlement_entry_id
                              AND sale.shop_id = sale_assignment.shop_id
                             WHERE sale_assignment.period_id = OLD.id
                               AND sale_assignment.shop_id = OLD.shop_id
                               AND sale_assignment.entry_type = 'SALE'
                               AND sale.order_id = debit.order_id
                         )
                   ))
                  OR (recalculation.reason_code = 'POST_APPROVAL_RECOVERY'
                   AND OLD.net_amount > 0
                   AND EXISTS (
                       SELECT 1 FROM shop_payout_recovery_debts recovery
                       JOIN shop_payouts source_payout
                         ON source_payout.id = recovery.payout_id AND source_payout.shop_id = recovery.shop_id
                       JOIN shop_settlement_periods source_period
                         ON source_period.id = source_payout.settlement_period_id
                        AND source_period.shop_id = source_payout.shop_id
                       WHERE recovery.id = recalculation.affected_recovery_debt_id
                         AND recovery.shop_id = recalculation.shop_id
                         AND recovery.outstanding_amount > 0
                         AND recovery.created_at >= OLD.approved_at
                         AND source_period.period_start_at < OLD.period_start_at
                         AND NOT EXISTS (
                             SELECT 1 FROM shop_settlement_recovery_offsets reserved
                             WHERE reserved.recovery_debt_id = recovery.id
                               AND reserved.shop_id = recovery.shop_id
                               AND reserved.period_id = OLD.id
                               AND reserved.status IN ('RESERVED', 'APPLIED')
                         )
                   ))
              )
        ) INTO valid_recalculation;
        IF NOT valid_recalculation
           OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL
           OR NEW.last_recalculation_id IS NOT DISTINCT FROM OLD.last_recalculation_id THEN
            RAISE EXCEPTION 'Approved settlement can return to draft only through audited debit recalculation';
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'APPROVED')
        OR (OLD.status = 'APPROVED' AND NEW.status = 'PROCESSING')
        OR (OLD.status = 'PROCESSING' AND NEW.status = 'PAID')
    ) THEN
        RAISE EXCEPTION 'Invalid settlement period state transition';
    END IF;

    IF OLD.status = 'PROCESSING' AND NEW.status = 'PAID' THEN
        SELECT coalesce(sum(applied.amount), 0) INTO reserved_recovery_amount
        FROM shop_settlement_recovery_offsets applied
        WHERE applied.period_id = NEW.id AND applied.shop_id = NEW.shop_id
          AND applied.status = 'RESERVED';
        IF reserved_recovery_amount <> NEW.recovery_debit_amount THEN
            RAISE EXCEPTION 'Paid settlement recovery amount must match its reserved offsets';
        END IF;
    END IF;

    IF (NEW.credit_amount IS DISTINCT FROM OLD.credit_amount
        OR NEW.debit_amount IS DISTINCT FROM OLD.debit_amount
        OR NEW.recovery_debit_amount IS DISTINCT FROM OLD.recovery_debit_amount
        OR NEW.net_amount IS DISTINCT FROM OLD.net_amount
        OR NEW.entry_count IS DISTINCT FROM OLD.entry_count)
       AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status = 'DRAFT')
           OR (OLD.status = 'APPROVED' AND NEW.status = 'DRAFT'
               AND coalesce(valid_recalculation, false))
       ) THEN
        RAISE EXCEPTION 'Only draft settlement period totals can change';
    END IF;

    IF NEW.last_recalculation_id IS DISTINCT FROM OLD.last_recalculation_id
       AND NOT (OLD.status = 'APPROVED' AND NEW.status = 'DRAFT') THEN
        RAISE EXCEPTION 'Settlement recalculation reference may change only when an approved period is reset';
    END IF;

    IF OLD.approved_at IS NOT NULL AND NOT (OLD.status = 'APPROVED' AND NEW.status = 'DRAFT')
       AND (NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.approved_by IS DISTINCT FROM OLD.approved_by) THEN
        RAISE EXCEPTION 'Settlement approval actor evidence is immutable';
    END IF;
    IF OLD.processing_at IS NOT NULL AND
       (NEW.processing_at IS DISTINCT FROM OLD.processing_at OR NEW.processing_by IS DISTINCT FROM OLD.processing_by) THEN
        RAISE EXCEPTION 'Settlement processing actor evidence is immutable';
    END IF;
    IF OLD.paid_at IS NOT NULL AND
       (NEW.paid_at IS DISTINCT FROM OLD.paid_at OR NEW.paid_by IS DISTINCT FROM OLD.paid_by) THEN
        RAISE EXCEPTION 'Settlement paid actor evidence is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION ensure_shop_settlement_period_is_draft()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM shop_settlement_periods p
        WHERE p.id = NEW.period_id AND p.shop_id = NEW.shop_id AND p.status = 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'Entries can only be assigned to a draft settlement period';
    END IF;
    IF EXISTS (
        SELECT 1 FROM shop_payout_recovery_debts recovery
        WHERE recovery.refund_entry_id = NEW.settlement_entry_id AND recovery.shop_id = NEW.shop_id
    ) THEN
        RAISE EXCEPTION 'Paid-sale recovery refunds are offset through recovery carry-forward, not entry assignment';
    END IF;
    RETURN NEW;
END $$;
