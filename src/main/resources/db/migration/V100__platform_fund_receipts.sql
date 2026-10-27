CREATE TABLE platform_fund_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_code VARCHAR(32) NOT NULL CHECK (source_code IN (
        'GATEWAY_SETTLEMENT', 'COD_REMITTANCE', 'CARRIER_REMITTANCE', 'MANUAL')),
    provider_code VARCHAR(40) NOT NULL,
    provider_account_reference VARCHAR(120) NOT NULL,
    environment VARCHAR(20) NOT NULL CHECK (environment IN (
        'PRODUCTION', 'SANDBOX', 'STAGING', 'INTERNAL', 'MANUAL')),
    source_identity VARCHAR(190) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    gross_amount BIGINT NOT NULL CHECK (gross_amount >= 0),
    fee_amount BIGINT NOT NULL CHECK (fee_amount >= 0),
    deduction_amount BIGINT NOT NULL CHECK (deduction_amount >= 0),
    net_amount BIGINT NOT NULL CHECK (net_amount >= 0),
    external_reference VARCHAR(190),
    evidence_reference VARCHAR(512),
    evidence_note VARCHAR(1000),
    received_at TIMESTAMPTZ(6) NOT NULL,
    recorded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_platform_fund_receipt_balance
        CHECK (gross_amount = fee_amount + deduction_amount + net_amount),
    CONSTRAINT uq_platform_fund_receipt_source
        UNIQUE (source_code, provider_code, provider_account_reference, environment, source_identity)
);

CREATE INDEX idx_platform_fund_receipts_received_at
    ON platform_fund_receipts(received_at DESC, id);
CREATE INDEX idx_platform_fund_receipts_environment
    ON platform_fund_receipts(environment, source_code, received_at DESC);

CREATE TABLE platform_fund_receipt_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id UUID NOT NULL REFERENCES platform_fund_receipts(id) ON DELETE RESTRICT,
    allocation_key VARCHAR(190) NOT NULL,
    source_kind VARCHAR(32) NOT NULL CHECK (source_kind IN (
        'ONLINE_PAYMENT', 'COD_REMITTANCE', 'CARRIER_REMITTANCE', 'OTHER')),
    source_reference VARCHAR(190) NOT NULL,
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    order_id UUID REFERENCES orders(id) ON DELETE RESTRICT,
    fulfillment_id UUID REFERENCES order_fulfillments(id) ON DELETE RESTRICT,
    gross_amount BIGINT NOT NULL CHECK (gross_amount >= 0),
    fee_amount BIGINT NOT NULL CHECK (fee_amount >= 0),
    deduction_amount BIGINT NOT NULL CHECK (deduction_amount >= 0),
    net_amount BIGINT NOT NULL CHECK (net_amount >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_platform_fund_allocation_balance
        CHECK (gross_amount = fee_amount + deduction_amount + net_amount),
    CONSTRAINT uq_platform_fund_receipt_allocation UNIQUE (receipt_id, allocation_key)
);

CREATE INDEX idx_platform_fund_receipt_alloc_shop
    ON platform_fund_receipt_allocations(shop_id, receipt_id);
CREATE INDEX idx_platform_fund_receipt_alloc_order
    ON platform_fund_receipt_allocations(order_id, receipt_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_platform_fund_receipt_alloc_fulfillment
    ON platform_fund_receipt_allocations(fulfillment_id, receipt_id) WHERE fulfillment_id IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_platform_fund_receipt_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; record a correcting receipt instead', TG_TABLE_NAME;
END $$;

CREATE TRIGGER trg_platform_fund_receipts_append_only
    BEFORE UPDATE OR DELETE ON platform_fund_receipts FOR EACH ROW
    EXECUTE FUNCTION prevent_platform_fund_receipt_mutation();
CREATE TRIGGER trg_platform_fund_receipt_allocations_append_only
    BEFORE UPDATE OR DELETE ON platform_fund_receipt_allocations FOR EACH ROW
    EXECUTE FUNCTION prevent_platform_fund_receipt_mutation();
