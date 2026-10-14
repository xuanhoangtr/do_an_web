CREATE INDEX IF NOT EXISTS idx_orders_dashboard_paid_at
    ON orders (paid_at)
    WHERE payment_status = 'SUCCEEDED' AND paid_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_orders_dashboard_legacy_created_at
    ON orders (created_at)
    WHERE payment_status = 'SUCCEEDED' AND paid_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orders_dashboard_created_at
    ON orders (created_at);

CREATE INDEX IF NOT EXISTS idx_users_dashboard_created_at
    ON users (created_at)
    WHERE deleted_at IS NULL;
