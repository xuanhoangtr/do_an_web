-- Runtime order status is payment-only. Preserve legacy fulfillment history
-- before collapsing those old values to PAID; no business row is deleted.
CREATE TABLE IF NOT EXISTS legacy_order_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    legacy_status VARCHAR(30) NOT NULL,
    captured_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO legacy_order_status_history (order_id, legacy_status)
SELECT id, status
FROM orders
WHERE status IN ('CONFIRMED','PROCESSING','SHIPPING','SHIPPED','DELIVERED','RETURNED')
  AND NOT EXISTS (
      SELECT 1 FROM legacy_order_status_history h
      WHERE h.order_id = orders.id AND h.legacy_status = orders.status
  );

UPDATE orders
SET status = 'PAID'
WHERE status IN ('CONFIRMED','PROCESSING','SHIPPING','SHIPPED','DELIVERED','RETURNED');

DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.orders') IS NOT NULL THEN
        FOR c IN
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'orders'::regclass
              AND contype = 'c'
              AND pg_get_constraintdef(oid) ILIKE '%status%'
        LOOP
            EXECUTE format('ALTER TABLE orders DROP CONSTRAINT IF EXISTS %I', c);
        END LOOP;
        ALTER TABLE orders ADD CONSTRAINT orders_status_check_payment_only
            CHECK (status IN ('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_orders_status_expires_at
    ON orders(status, expires_at);
CREATE INDEX IF NOT EXISTS idx_orders_status_paid_at
    ON orders(status, paid_at, created_at);
