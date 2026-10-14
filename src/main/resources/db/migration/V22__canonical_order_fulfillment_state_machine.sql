-- Final append-only assertion of the payment-only order runtime contract.
-- Legacy fulfillment history/columns remain migration data and are never
-- restored to orders.status or used by Java runtime routes.
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
        ALTER TABLE orders
            ADD CONSTRAINT orders_status_check_payment_only_final
            CHECK (status IN ('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_orders_user_created_at
    ON orders(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_orders_status_expires_at
    ON orders(status, expires_at);
