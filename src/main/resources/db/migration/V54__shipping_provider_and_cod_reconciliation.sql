-- P2 shipping provider contract, immutable quote snapshots and per-fulfillment COD.
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'COD_RECONCILED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SHIPPING_EVENT_RECEIVED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SHIPMENT_OPERATION_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SETTLEMENT_PERIOD_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PAYOUT_DESTINATION_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PAYOUT_STATUS_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SUPPORT_TICKET_CREATED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SUPPORT_TICKET_REPLIED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SUPPORT_TICKET_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'RISK_EVENT_RESOLVED';

ALTER TABLE order_fulfillments DROP CONSTRAINT IF EXISTS order_fulfillments_status_check;

UPDATE order_fulfillments SET status = 'IN_TRANSIT' WHERE status = 'SHIPPING';

ALTER TABLE order_fulfillments ADD CONSTRAINT order_fulfillments_status_check_p2
    CHECK (status IN ('CONFIRMED','PROCESSING','PICKUP_PENDING','PICKED_UP','IN_TRANSIT',
        'DELIVERY_FAILED','RETRY_DELIVERY','DELIVERED','RETURNING','RETURNED','CANCELLED'));

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check_payment_only_final;
ALTER TABLE orders ADD CONSTRAINT orders_status_check_p2
    CHECK (status IN ('PENDING','PAYMENT_REVIEW','PAID','CANCELLED','EXPIRED','REFUNDED'));

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check_p2
    CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','NOT_COLLECTED',
        'RECONCILIATION_REQUIRED','REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED','REFUND_FAILED'));
ALTER TABLE payments ADD COLUMN IF NOT EXISTS collected_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS final_due_amount BIGINT;
ALTER TABLE payments ADD CONSTRAINT payments_cod_amounts_check
    CHECK (collected_amount >= 0 AND (final_due_amount IS NULL OR final_due_amount >= 0));

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_payment_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_payment_status_check_p2
    CHECK (payment_status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','NOT_COLLECTED',
        'RECONCILIATION_REQUIRED','REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED','REFUND_FAILED'));

CREATE TABLE shipping_quotes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    provider_code VARCHAR(40) NOT NULL,
    method_code VARCHAR(40) NOT NULL,
    service_code VARCHAR(80) NOT NULL,
    fee BIGINT NOT NULL CHECK (fee >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    cart_hash CHAR(64) NOT NULL CHECK (cart_hash ~ '^[0-9a-f]{64}$'),
    destination_hash CHAR(64) NOT NULL CHECK (destination_hash ~ '^[0-9a-f]{64}$'),
    provider_quote_ref VARCHAR(190),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    expires_at TIMESTAMPTZ(6) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_shipping_quotes_customer_expiry ON shipping_quotes(customer_id, expires_at DESC);

CREATE TABLE order_shipping_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
    quote_id UUID REFERENCES shipping_quotes(id) ON DELETE RESTRICT,
    method_code VARCHAR(40) NOT NULL,
    provider_code VARCHAR(40) NOT NULL,
    service_code VARCHAR(80) NOT NULL,
    quoted_fee BIGINT NOT NULL CHECK (quoted_fee >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    destination_snapshot JSONB NOT NULL,
    provider_quote_ref VARCHAR(190),
    quote_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    quoted_at TIMESTAMPTZ(6) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fulfillment_shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id UUID NOT NULL REFERENCES order_fulfillments(id) ON DELETE RESTRICT,
    provider_code VARCHAR(40) NOT NULL,
    method_code VARCHAR(40) NOT NULL,
    service_code VARCHAR(80) NOT NULL,
    provider_shipment_id VARCHAR(190),
    provider_reference VARCHAR(190),
    tracking_number VARCHAR(120),
    label_reference VARCHAR(512),
    status VARCHAR(30) NOT NULL,
    last_provider_sequence BIGINT,
    last_provider_event_at TIMESTAMPTZ(6),
    cancelled_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fulfillment_shipments_status_check CHECK (status IN (
        'PICKUP_PENDING','PICKED_UP','IN_TRANSIT','DELIVERY_FAILED','RETRY_DELIVERY',
        'DELIVERED','RETURNING','RETURNED','CANCELLED'))
);
CREATE UNIQUE INDEX uq_fulfillment_shipments_active ON fulfillment_shipments(fulfillment_id)
    WHERE status NOT IN ('DELIVERED','RETURNED','CANCELLED');
CREATE UNIQUE INDEX uq_fulfillment_shipments_provider_id
    ON fulfillment_shipments(provider_code, provider_shipment_id) WHERE provider_shipment_id IS NOT NULL;
CREATE INDEX idx_fulfillment_shipments_fulfillment ON fulfillment_shipments(fulfillment_id, created_at DESC);

CREATE TABLE shipping_provider_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id UUID NOT NULL REFERENCES fulfillment_shipments(id) ON DELETE RESTRICT,
    operation VARCHAR(30) NOT NULL CHECK (operation IN ('CREATE','CANCEL','PICKUP','TRACK')),
    idempotency_key VARCHAR(190) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING','PROCESSING','SUCCEEDED','FAILED','AMBIGUOUS','DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    provider_reference VARCHAR(190),
    sanitized_request JSONB NOT NULL DEFAULT '{}'::jsonb,
    sanitized_response JSONB,
    last_error VARCHAR(1000),
    next_attempt_at TIMESTAMPTZ(6),
    processed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_shipping_operations_due ON shipping_provider_operations(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING','FAILED');
CREATE INDEX idx_shipping_operations_shipment ON shipping_provider_operations(shipment_id, created_at DESC);

CREATE TABLE shipping_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id UUID REFERENCES fulfillment_shipments(id) ON DELETE RESTRICT,
    provider_code VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(190) NOT NULL,
    provider_status VARCHAR(80) NOT NULL,
    canonical_status VARCHAR(30),
    provider_sequence BIGINT,
    event_at TIMESTAMPTZ(6),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    sanitized_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    outcome VARCHAR(30) NOT NULL CHECK (outcome IN (
        'APPLIED','DUPLICATE','IGNORED_STALE','REJECTED_INVALID','UNKNOWN_SHIPMENT')),
    rejection_reason VARCHAR(500),
    received_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMPTZ(6),
    UNIQUE (provider_code, provider_event_id)
);
CREATE INDEX idx_shipping_events_shipment_time ON shipping_events(shipment_id, received_at DESC);

CREATE TABLE cod_reconciliations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
    original_due_amount BIGINT NOT NULL CHECK (original_due_amount >= 0),
    final_due_amount BIGINT NOT NULL DEFAULT 0 CHECK (final_due_amount >= 0),
    collected_amount BIGINT NOT NULL DEFAULT 0 CHECK (collected_amount >= 0),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN' CHECK (status IN (
        'OPEN','AWAITING_AMOUNT','RECONCILED','NOT_COLLECTED','REVIEW_REQUIRED','RESOLVED')),
    resolution_reference VARCHAR(190),
    resolution_note VARCHAR(1000),
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cod_reconciliations_status ON cod_reconciliations(status, updated_at);
CREATE INDEX idx_cod_reconciliations_resolved_by ON cod_reconciliations(resolved_by) WHERE resolved_by IS NOT NULL;

CREATE TABLE cod_obligations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_id UUID NOT NULL REFERENCES cod_reconciliations(id) ON DELETE RESTRICT,
    fulfillment_id UUID NOT NULL UNIQUE REFERENCES order_fulfillments(id) ON DELETE RESTRICT,
    merchandise_due BIGINT NOT NULL CHECK (merchandise_due >= 0),
    allocated_shipping_fee BIGINT NOT NULL CHECK (allocated_shipping_fee >= 0),
    expected_collectable BIGINT NOT NULL CHECK (expected_collectable >= 0),
    actual_collected BIGINT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING','AWAITING_AMOUNT','COLLECTED','NOT_COLLECTED','SHORT','OVER','WAIVED')),
    collection_reference VARCHAR(190),
    collected_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cod_obligation_actual_check CHECK (actual_collected IS NULL OR actual_collected >= 0)
);
CREATE INDEX idx_cod_obligations_reconciliation ON cod_obligations(reconciliation_id, fulfillment_id);
CREATE INDEX idx_cod_obligations_status ON cod_obligations(status, updated_at);

-- Existing order rows have already snapshotted destination fields; preserve them without recalculation.
INSERT INTO order_shipping_snapshots (
    order_id, method_code, provider_code, service_code, quoted_fee, currency,
    destination_snapshot, provider_quote_ref, quote_metadata, quoted_at
)
SELECT o.id, 'STANDARD', 'INTERNAL', 'LEGACY', o.shipping_fee, o.currency,
       jsonb_build_object('recipientName', o.shipping_recipient_name, 'phone', o.shipping_phone,
           'address', o.shipping_address, 'ward', o.shipping_ward, 'district', o.shipping_district,
           'province', o.shipping_province),
       NULL, jsonb_build_object('source', 'LEGACY_ORDER_SNAPSHOT'), o.created_at
FROM orders o
ON CONFLICT (order_id) DO NOTHING;

-- Legacy single-collector COD cannot be safely split after a package has progressed.
INSERT INTO cod_reconciliations(order_id, original_due_amount, status)
SELECT o.id, o.total_amount,
       CASE WHEN EXISTS (SELECT 1 FROM order_fulfillments f WHERE f.order_id=o.id
                         AND f.status IN ('PICKED_UP','IN_TRANSIT','DELIVERY_FAILED','RETRY_DELIVERY','DELIVERED','RETURNING','RETURNED'))
            THEN 'REVIEW_REQUIRED' ELSE 'OPEN' END
FROM orders o WHERE upper(o.payment_method)='COD'
ON CONFLICT (order_id) DO NOTHING;

CREATE OR REPLACE FUNCTION prevent_shipping_immutable_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '% is append-only', TG_TABLE_NAME; END $$;
CREATE TRIGGER trg_order_shipping_snapshots_append_only
    BEFORE UPDATE OR DELETE ON order_shipping_snapshots FOR EACH ROW
    EXECUTE FUNCTION prevent_shipping_immutable_mutation();
CREATE TRIGGER trg_shipping_events_append_only
    BEFORE UPDATE OR DELETE ON shipping_events FOR EACH ROW
    EXECUTE FUNCTION prevent_shipping_immutable_mutation();

