CREATE TABLE shop_commission_policies (
    policy_version BIGINT PRIMARY KEY,
    rate_bps INTEGER NOT NULL CHECK (rate_bps BETWEEN 0 AND 10000),
    effective_at TIMESTAMPTZ(6) NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO shop_commission_policies(policy_version, rate_bps, effective_at)
VALUES (1, 0, TIMESTAMPTZ '2000-01-01 00:00:00+00');

CREATE INDEX idx_shop_commission_policy_effective
    ON shop_commission_policies(effective_at DESC, policy_version DESC);

CREATE OR REPLACE FUNCTION prevent_shop_commission_policy_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Commission policy is versioned and append-only';
END $$;

CREATE TRIGGER trg_shop_commission_policies_append_only
    BEFORE UPDATE OR DELETE ON shop_commission_policies FOR EACH ROW
    EXECUTE FUNCTION prevent_shop_commission_policy_mutation();

CREATE TABLE shop_finance_state (
    shop_id UUID PRIMARY KEY REFERENCES shops(id) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO shop_finance_state(shop_id)
SELECT id FROM shops ON CONFLICT (shop_id) DO NOTHING;

CREATE OR REPLACE FUNCTION ensure_shop_finance_state()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO shop_finance_state(shop_id) VALUES (NEW.id) ON CONFLICT (shop_id) DO NOTHING;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_ensure_shop_finance_state
    AFTER INSERT ON shops FOR EACH ROW EXECUTE FUNCTION ensure_shop_finance_state();

CREATE TABLE shop_order_financial_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    merchandise_gross BIGINT NOT NULL CHECK (merchandise_gross >= 0),
    shop_funded_discount BIGINT NOT NULL CHECK (shop_funded_discount >= 0),
    commission_base BIGINT NOT NULL CHECK (commission_base >= 0),
    commission_rate_bps INTEGER NOT NULL CHECK (commission_rate_bps BETWEEN 0 AND 10000),
    commission_amount BIGINT NOT NULL CHECK (commission_amount >= 0),
    vendor_payable BIGINT NOT NULL CHECK (vendor_payable >= 0),
    policy_version BIGINT NOT NULL REFERENCES shop_commission_policies(policy_version) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_order_financial_snapshot_order UNIQUE (order_id),
    CONSTRAINT uq_shop_order_financial_snapshot_id_order_shop UNIQUE (id, order_id, shop_id),
    CONSTRAINT fk_shop_order_financial_snapshot_order_shop
        FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_order_financial_snapshot_base
        CHECK (commission_base = merchandise_gross - shop_funded_discount),
    CONSTRAINT ck_shop_order_financial_snapshot_commission
        CHECK (commission_amount = FLOOR((commission_base::numeric * commission_rate_bps) / 10000)::bigint),
    CONSTRAINT ck_shop_order_financial_snapshot_payable
        CHECK (vendor_payable = commission_base - commission_amount)
);

CREATE INDEX idx_shop_order_financial_snapshot_shop_order
    ON shop_order_financial_snapshots(shop_id, order_id);

CREATE OR REPLACE FUNCTION prevent_shop_order_financial_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Order financial snapshots are immutable';
END $$;

CREATE TRIGGER trg_shop_order_financial_snapshots_append_only
    BEFORE UPDATE OR DELETE ON shop_order_financial_snapshots FOR EACH ROW
    EXECUTE FUNCTION prevent_shop_order_financial_snapshot_mutation();

-- Preserve an immutable zero-rate baseline for pre-existing Vendor orders. New
-- checkouts snapshot the then-current policy in ShopFinancialService.
WITH merchandise AS (
    SELECT order_id, shop_id, SUM(line_total)::bigint AS merchandise_gross
    FROM order_items
    GROUP BY order_id, shop_id
), shop_discounts AS (
    SELECT order_id, shop_id, SUM(discount_amount)::bigint AS discount_amount
    FROM order_applied_discounts
    WHERE slot = 'MERCHANDISE' AND funding_source = 'SHOP'
    GROUP BY order_id, shop_id
), financials AS (
    SELECT o.id AS order_id, o.shop_id,
           COALESCE(m.merchandise_gross, 0)::bigint AS merchandise_gross,
           LEAST(COALESCE(d.discount_amount, 0), COALESCE(m.merchandise_gross, 0))::bigint
               AS shop_funded_discount,
           COALESCE(o.created_at, CURRENT_TIMESTAMP) AS created_at
    FROM orders o
    LEFT JOIN merchandise m ON m.order_id = o.id AND m.shop_id = o.shop_id
    LEFT JOIN shop_discounts d ON d.order_id = o.id AND d.shop_id = o.shop_id
    WHERE o.shop_id <> '00000000-0000-4000-8000-000000000001'::uuid
)
INSERT INTO shop_order_financial_snapshots(
    order_id, shop_id, merchandise_gross, shop_funded_discount, commission_base,
    commission_rate_bps, commission_amount, vendor_payable, policy_version, created_at)
SELECT f.order_id, f.shop_id, f.merchandise_gross, f.shop_funded_discount,
       f.merchandise_gross - f.shop_funded_discount, 0, 0,
       f.merchandise_gross - f.shop_funded_discount, 1, f.created_at
FROM financials f
ON CONFLICT (order_id) DO NOTHING;

CREATE TABLE shop_settlement_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    order_id UUID,
    entry_type VARCHAR(20) NOT NULL CHECK (entry_type IN ('SALE', 'REFUND', 'ADJUSTMENT', 'RECOVERY')),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    source_identity VARCHAR(190) NOT NULL UNIQUE,
    source_reference VARCHAR(190) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_settlement_entry_order_shop
        FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_shop_settlement_sale_order
    ON shop_settlement_entries(shop_id, order_id) WHERE entry_type = 'SALE';
CREATE INDEX idx_shop_settlement_entries_history
    ON shop_settlement_entries(shop_id, created_at DESC, id);
CREATE INDEX idx_shop_settlement_entries_order
    ON shop_settlement_entries(order_id, created_at, id) WHERE order_id IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_shop_settlement_entry_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Settlement entries are append-only; add a reversal or adjustment';
END $$;

CREATE TRIGGER trg_shop_settlement_entries_append_only
    BEFORE UPDATE OR DELETE ON shop_settlement_entries FOR EACH ROW
    EXECUTE FUNCTION prevent_shop_settlement_entry_mutation();
