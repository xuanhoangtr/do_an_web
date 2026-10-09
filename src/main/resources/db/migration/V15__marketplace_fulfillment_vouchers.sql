-- Canonical marketplace extensions. Legacy orders remain readable; no status is
-- rewritten by this migration.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS carrier_id UUID;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ(6);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS returned_at TIMESTAMPTZ(6);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS vendor_id UUID;
CREATE INDEX IF NOT EXISTS idx_order_items_vendor_id ON order_items(vendor_id);
UPDATE orders
SET payment_status = CASE
    WHEN status IN ('PAID','CONFIRMED','PROCESSING','SHIPPING','SHIPPED','DELIVERED','RETURNED')
        THEN 'SUCCEEDED'
    WHEN status = 'REFUNDED' THEN 'REFUNDED'
    WHEN status IN ('CANCELLED','EXPIRED') THEN 'FAILED'
    ELSE payment_status
END
WHERE payment_status = 'PENDING';
UPDATE order_items item
SET vendor_id = product.vendor_id
FROM catalog_products product
WHERE item.product_id = product.id AND item.vendor_id IS NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_payment_status_check') THEN
        ALTER TABLE orders ADD CONSTRAINT orders_payment_status_check
            CHECK (payment_status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','REFUNDED'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_orders_payment_status ON orders(payment_status, created_at);

CREATE TABLE IF NOT EXISTS carriers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    contact_phone VARCHAR(40),
    contact_url VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_orders_carrier') THEN
        ALTER TABLE orders ADD CONSTRAINT fk_orders_carrier FOREIGN KEY (carrier_id) REFERENCES carriers(id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS commission_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    percent INTEGER NOT NULL CHECK (percent >= 0 AND percent <= 100),
    active BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_fulfillments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    vendor_id UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('CONFIRMED','PROCESSING','SHIPPING','DELIVERED','RETURNED','CANCELLED')),
    shipper_id UUID REFERENCES users(id),
    carrier_id UUID REFERENCES carriers(id),
    tracking_number VARCHAR(120),
    commission_percent INTEGER NOT NULL DEFAULT 0 CHECK (commission_percent >= 0 AND commission_percent <= 100),
    commission_amount BIGINT NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ(6),
    returned_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_fulfillments_order_vendor UNIQUE(order_id, vendor_id)
);
-- Platform-owned catalog items have no vendor_id. They still need a
-- fulfillment so a shipper can deliver them; older partial deployments may
-- have created this column as NOT NULL or without the FK.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 'order_fulfillments'
                     AND column_name = 'vendor_id') THEN
        ALTER TABLE order_fulfillments ADD COLUMN vendor_id UUID;
    END IF;
END $$;
ALTER TABLE order_fulfillments ALTER COLUMN vendor_id DROP NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'order_fulfillments'::regclass
                     AND contype = 'f'
                     AND pg_get_constraintdef(oid) ILIKE '%vendor_id%') THEN
        ALTER TABLE order_fulfillments ADD CONSTRAINT fk_fulfillments_vendor
            FOREIGN KEY (vendor_id) REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_fulfillments_vendor_status ON order_fulfillments(vendor_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_fulfillments_shipper_status ON order_fulfillments(shipper_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_fulfillments_order ON order_fulfillments(order_id);

CREATE TABLE IF NOT EXISTS order_fulfillment_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id UUID NOT NULL REFERENCES order_fulfillments(id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL UNIQUE REFERENCES order_items(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fulfillment_items_fulfillment ON order_fulfillment_items(fulfillment_id);

CREATE TABLE IF NOT EXISTS vouchers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL UNIQUE,
    vendor_id UUID REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL CHECK (type IN ('PERCENT','FIXED')),
    value_amount BIGINT NOT NULL CHECK (value_amount > 0),
    minimum_order_amount BIGINT NOT NULL DEFAULT 0,
    maximum_discount_amount BIGINT,
    starts_at TIMESTAMPTZ(6) NOT NULL,
    ends_at TIMESTAMPTZ(6) NOT NULL,
    total_quota INTEGER,
    per_user_quota INTEGER,
    used_count INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_vouchers_scope_active_window ON vouchers(vendor_id, active, starts_at, ends_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_vouchers_code_lower ON vouchers (lower(code));

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'vouchers_window_check') THEN
        ALTER TABLE vouchers ADD CONSTRAINT vouchers_window_check CHECK (ends_at > starts_at);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'vouchers_quota_check') THEN
        ALTER TABLE vouchers ADD CONSTRAINT vouchers_quota_check CHECK (
            (total_quota IS NULL OR total_quota >= 0)
            AND (per_user_quota IS NULL OR per_user_quota >= 0)
            AND (total_quota IS NULL OR used_count <= total_quota)
        );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'vouchers_discount_cap_check') THEN
        ALTER TABLE vouchers ADD CONSTRAINT vouchers_discount_cap_check
            CHECK (maximum_discount_amount IS NULL OR maximum_discount_amount >= 0);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS voucher_usages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    voucher_id UUID NOT NULL REFERENCES vouchers(id),
    user_id UUID NOT NULL REFERENCES users(id),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    discount_amount BIGINT NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_voucher_usages_voucher_user ON voucher_usages(voucher_id, user_id);
CREATE INDEX IF NOT EXISTS idx_voucher_usages_order ON voucher_usages(order_id);
