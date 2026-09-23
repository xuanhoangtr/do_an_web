-- Add the immutable delivery snapshot and the ownership fields required by
-- the order lifecycle. Every statement is safe against the V1 baseline and
-- an already partially upgraded deployment.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_recipient_name VARCHAR(160);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_phone VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_address VARCHAR(500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_fee NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_method VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_number VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ(6);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_ward VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_district VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_province VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipper_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_orders_shipper'
          AND conrelid = 'orders'::regclass
    ) THEN
        ALTER TABLE orders
            ADD CONSTRAINT fk_orders_shipper FOREIGN KEY (shipper_id) REFERENCES users(id);
    END IF;
END $$;

-- Preserve the original checkout address as the line address and fill only
-- fields that are absent. Existing explicit snapshots always win.
UPDATE orders o
SET shipping_recipient_name = COALESCE(o.shipping_recipient_name, s.recipient_name),
    shipping_phone = COALESCE(o.shipping_phone, s.phone),
    shipping_address = COALESCE(o.shipping_address, s.address_line),
    shipping_ward = COALESCE(o.shipping_ward, s.ward),
    shipping_district = COALESCE(o.shipping_district, s.district),
    shipping_province = COALESCE(o.shipping_province, s.province)
FROM ship_infos s
WHERE o.ship_info_id = s.id;

-- Keep the complete fulfillment state machine in the database contract. The
-- SHIPPED value remains readable for old rows, while new code uses SHIPPING.
DO $$
DECLARE constraint_name TEXT;
BEGIN
    SELECT c.conname INTO constraint_name
    FROM pg_constraint c
    WHERE c.conrelid = 'orders'::regclass
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) ILIKE '%status%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_name);
    END IF;

END $$;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check_standard
    CHECK (status IN ('PENDING','PAID','CONFIRMED','PROCESSING','SHIPPING',
                      'SHIPPED','DELIVERED','CANCELLED','EXPIRED','REFUNDED','RETURNED'));

CREATE INDEX IF NOT EXISTS idx_orders_shipper_status
    ON orders (shipper_id, status, created_at);
