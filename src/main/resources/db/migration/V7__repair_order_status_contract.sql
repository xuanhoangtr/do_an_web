-- Repair databases that already ran the first V2, which accidentally
-- narrowed the order status check to payment-only states.
DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'orders'::regclass
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check_lifecycle
    CHECK (status IN ('PENDING','PAID','CONFIRMED','PROCESSING','SHIPPING',
                      'SHIPPED','DELIVERED','CANCELLED','EXPIRED','REFUNDED','RETURNED'));

ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_ward VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_district VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_province VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipper_id UUID;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_number VARCHAR(120);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_orders_shipper' AND conrelid = 'orders'::regclass
    ) THEN
        ALTER TABLE orders ADD CONSTRAINT fk_orders_shipper
            FOREIGN KEY (shipper_id) REFERENCES users(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_orders_shipper_status
    ON orders (shipper_id, status, created_at);
