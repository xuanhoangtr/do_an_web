CREATE TABLE shop_finance_holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL,
    source_type VARCHAR(30) NOT NULL CHECK (source_type IN (
        'RETURN_CASE', 'RETURN_RESOLUTION', 'RETURN_DISPUTE', 'REFUND')),
    source_id UUID NOT NULL,
    reason_code VARCHAR(40) NOT NULL CHECK (length(btrim(reason_code)) > 0),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RELEASED')),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMPTZ(6),
    released_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT uq_shop_finance_hold_source UNIQUE (shop_id, source_type, source_id),
    CONSTRAINT fk_shop_finance_hold_order_shop
        FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_finance_hold_release_metadata CHECK (
        (status = 'ACTIVE' AND released_at IS NULL AND released_by IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL)
    )
);

CREATE INDEX idx_shop_finance_holds_active_shop_order
    ON shop_finance_holds(shop_id, order_id, created_at DESC)
    WHERE status = 'ACTIVE';

CREATE OR REPLACE FUNCTION prevent_shop_finance_hold_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.order_id IS DISTINCT FROM OLD.order_id
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_id IS DISTINCT FROM OLD.source_id
       OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Shop finance hold source and amount are immutable';
    END IF;

    IF NEW.status = OLD.status THEN
        RAISE EXCEPTION 'Shop finance hold metadata may only change with a status transition';
    END IF;
    IF NEW.status = 'RELEASED' AND NEW.released_at IS NULL THEN
        RAISE EXCEPTION 'Released shop finance holds require a release timestamp';
    END IF;
    IF NEW.status = 'ACTIVE' AND (NEW.released_at IS NOT NULL OR NEW.released_by IS NOT NULL) THEN
        RAISE EXCEPTION 'Active shop finance holds cannot have release metadata';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_finance_holds_immutable
    BEFORE UPDATE ON shop_finance_holds FOR EACH ROW EXECUTE FUNCTION prevent_shop_finance_hold_mutation();

CREATE TRIGGER trg_shop_finance_holds_no_delete
    BEFORE DELETE ON shop_finance_holds FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

CREATE OR REPLACE FUNCTION validate_shop_finance_hold_source()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_type = 'RETURN_CASE' AND NOT EXISTS (
        SELECT 1 FROM return_cases c
        WHERE c.id = NEW.source_id AND c.shop_id = NEW.shop_id AND c.order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'Return-case finance hold source must match its shop and order';
    ELSIF NEW.source_type = 'RETURN_RESOLUTION' AND NOT EXISTS (
        SELECT 1 FROM return_resolutions r
        JOIN return_cases c ON c.id = r.return_case_id
        WHERE r.id = NEW.source_id AND c.shop_id = NEW.shop_id AND c.order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'Return-resolution finance hold source must match its shop and order';
    ELSIF NEW.source_type = 'RETURN_DISPUTE' AND NOT EXISTS (
        SELECT 1 FROM return_disputes d
        JOIN return_cases c ON c.id = d.return_case_id
        WHERE d.id = NEW.source_id AND c.shop_id = NEW.shop_id AND c.order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'Return-dispute finance hold source must match its shop and order';
    ELSIF NEW.source_type = 'REFUND' AND NOT EXISTS (
        SELECT 1 FROM payment_provider_transactions t
        WHERE t.id = NEW.source_id AND t.order_id = NEW.order_id
          AND t.transaction_type = 'REFUND'
    ) THEN
        RAISE EXCEPTION 'Refund finance hold source must match its order';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_shop_finance_holds_source_integrity
    BEFORE INSERT ON shop_finance_holds FOR EACH ROW EXECUTE FUNCTION validate_shop_finance_hold_source();
