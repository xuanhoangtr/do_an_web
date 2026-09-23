ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_access_code UUID;

UPDATE orders
SET tracking_access_code = gen_random_uuid()
WHERE tracking_access_code IS NULL;

ALTER TABLE orders ALTER COLUMN tracking_access_code SET DEFAULT gen_random_uuid();
ALTER TABLE orders ALTER COLUMN tracking_access_code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_tracking_access_code
    ON orders(tracking_access_code);
