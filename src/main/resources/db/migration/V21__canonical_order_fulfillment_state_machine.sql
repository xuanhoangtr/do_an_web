-- Re-assert the payment-only runtime order state machine after the legacy
-- migrations. Legacy fulfillment history and columns remain queryable for
-- migration work but are never restored to orders.status or exposed by Java.
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
            ADD CONSTRAINT orders_status_check_payment_lifecycle
            CHECK (status IN ('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_orders_user_created_at
    ON orders(user_id, created_at DESC, id DESC);

-- Activate the already-present RBAC tables and backfill the legacy primary
-- role. The users.role column remains authoritative for old JWT consumers.
INSERT INTO roles (name, description, is_active)
VALUES
    ('USER', 'Default customer role', true),
    ('CUSTOMER', 'Customer role', true),
    ('VENDOR', 'Marketplace vendor role', true),
    ('SHIPPER', 'Legacy delivery role retained for migration only', true),
    ('MANAGER', 'Operations manager role', true),
    ('ADMIN', 'Administrator role', true),
    ('SUPER_ADMIN', 'System administrator role', true)
ON CONFLICT (name) DO UPDATE SET is_active = EXCLUDED.is_active;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.name = u.role::text
WHERE r.is_active
ON CONFLICT (user_id, role_id) DO NOTHING;
