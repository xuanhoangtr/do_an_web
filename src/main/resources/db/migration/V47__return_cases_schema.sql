-- Customer RMA aggregate. Fulfillment status remains the outbound shipment lifecycle.
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS voucher_discount_allocated BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS net_paid_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS return_reserved_quantity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS return_resolved_quantity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_refunded_amount BIGINT NOT NULL DEFAULT 0;

ALTER TABLE order_items DROP CONSTRAINT IF EXISTS order_items_return_quantity_check;
ALTER TABLE order_items ADD CONSTRAINT order_items_return_quantity_check
    CHECK (voucher_discount_allocated >= 0 AND net_paid_amount >= 0
        AND return_reserved_quantity >= 0 AND return_resolved_quantity >= 0
        AND return_reserved_quantity + return_resolved_quantity <= quantity);
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_shipping_refunded_amount_check;
ALTER TABLE orders ADD CONSTRAINT orders_shipping_refunded_amount_check
    CHECK (shipping_refunded_amount >= 0 AND shipping_refunded_amount <= shipping_fee);

CREATE TABLE IF NOT EXISTS return_cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number VARCHAR(40) NOT NULL UNIQUE,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    fulfillment_id UUID NOT NULL REFERENCES order_fulfillments(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    vendor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    reason VARCHAR(80) NOT NULL,
    policy_snapshot VARCHAR(2000) NOT NULL,
    request_deadline TIMESTAMPTZ(6) NOT NULL,
    ship_deadline TIMESTAMPTZ(6),
    dispute_deadline TIMESTAMPTZ(6),
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ(6),
    rejection_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT return_cases_status_check CHECK (status IN (
        'REQUESTED','UNDER_REVIEW','APPROVED','REJECTED','AWAITING_RETURN',
        'RETURN_IN_TRANSIT','RECEIVED','INSPECTED','RESOLUTION_PENDING',
        'REFUND_PENDING','REFUND_FAILED','EXCHANGE_PENDING','RESOLVED',
        'DISPUTED','CLOSED','EXPIRED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_return_cases_order_status ON return_cases(order_id, status, created_at);

CREATE TABLE IF NOT EXISTS return_case_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID NOT NULL REFERENCES return_cases(id) ON DELETE RESTRICT,
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE RESTRICT,
    requested_quantity INTEGER NOT NULL CHECK (requested_quantity > 0),
    approved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (approved_quantity >= 0),
    received_quantity INTEGER NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    resolved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (resolved_quantity >= 0),
    refund_amount BIGINT NOT NULL DEFAULT 0 CHECK (refund_amount >= 0),
    resolution_type VARCHAR(20),
    inspection_result VARCHAR(20),
    stock_disposition VARCHAR(20),
    UNIQUE (return_case_id, order_item_id)
);
CREATE INDEX IF NOT EXISTS idx_return_case_items_order_item ON return_case_items(order_item_id);

CREATE TABLE IF NOT EXISTS return_request_idempotency (
    customer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(190) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    return_case_id UUID UNIQUE REFERENCES return_cases(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS return_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID NOT NULL REFERENCES return_cases(id) ON DELETE RESTRICT,
    item_id UUID REFERENCES return_case_items(id) ON DELETE SET NULL,
    uploader_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    private_public_id VARCHAR(512) NOT NULL,
    mime_type VARCHAR(80) NOT NULL CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_return_evidence_case ON return_evidence(return_case_id, created_at);

CREATE TABLE IF NOT EXISTS return_shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID NOT NULL REFERENCES return_cases(id) ON DELETE RESTRICT,
    direction VARCHAR(30) NOT NULL CHECK (direction IN ('CUSTOMER_TO_VENDOR','VENDOR_TO_CUSTOMER')),
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED','IN_TRANSIT','DELIVERED','CANCELLED')),
    carrier VARCHAR(120), tracking_number VARCHAR(120),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_return_shipments_case_status ON return_shipments(return_case_id, status);

CREATE TABLE IF NOT EXISTS return_resolutions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID NOT NULL REFERENCES return_cases(id) ON DELETE RESTRICT,
    type VARCHAR(20) NOT NULL CHECK (type IN ('REFUND','EXCHANGE')),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SUCCEEDED','FAILED','MANUAL_REFUND_PENDING')),
    amount BIGINT NOT NULL DEFAULT 0 CHECK (amount >= 0),
    idempotency_key VARCHAR(190) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    payment_transaction_id UUID,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_return_resolutions_case_status ON return_resolutions(return_case_id, status);

CREATE TABLE IF NOT EXISTS return_resolution_items (
    resolution_id UUID NOT NULL REFERENCES return_resolutions(id) ON DELETE RESTRICT,
    return_case_item_id UUID NOT NULL REFERENCES return_case_items(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (resolution_id, return_case_item_id)
);

CREATE TABLE IF NOT EXISTS return_disputes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID NOT NULL REFERENCES return_cases(id) ON DELETE RESTRICT,
    opened_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','UPHELD','OVERTURNED','CLOSED')),
    decision VARCHAR(1000), decision_actor UUID REFERENCES users(id) ON DELETE SET NULL,
    target_status VARCHAR(32), created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_return_disputes_case_status ON return_disputes(return_case_id, status);

CREATE TABLE IF NOT EXISTS return_case_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID NOT NULL REFERENCES return_cases(id) ON DELETE RESTRICT,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_role VARCHAR(30), from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL,
    request_id VARCHAR(120), metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_return_case_history_case_created ON return_case_history(return_case_id, created_at);

CREATE TABLE IF NOT EXISTS inventory_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_case_id UUID REFERENCES return_cases(id) ON DELETE RESTRICT,
    return_resolution_id UUID REFERENCES return_resolutions(id) ON DELETE RESTRICT,
    product_id UUID NOT NULL REFERENCES catalog_products(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('RETURN_RESTOCK','EXCHANGE_OUT','EXCHANGE_RELEASE')),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (return_resolution_id, product_id, kind)
);

ALTER TABLE payment_provider_transactions ADD COLUMN IF NOT EXISTS return_resolution_id UUID REFERENCES return_resolutions(id) ON DELETE RESTRICT;
ALTER TABLE vendor_settlement_entries ADD COLUMN IF NOT EXISTS return_resolution_id UUID REFERENCES return_resolutions(id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_active_provider_refund
    ON payment_provider_transactions(return_resolution_id)
    WHERE transaction_type = 'REFUND' AND status IN ('PENDING','SUCCEEDED') AND return_resolution_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_settlement_adjustment
    ON vendor_settlement_entries(return_resolution_id)
    WHERE return_resolution_id IS NOT NULL;