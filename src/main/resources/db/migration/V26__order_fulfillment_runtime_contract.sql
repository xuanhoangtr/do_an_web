-- Runtime contract for the separate fulfillment lifecycle. orders.status
-- remains payment-only and is intentionally untouched here.
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS delivery_proof_url VARCHAR(2048);
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS delivery_proof_public_id VARCHAR(512);
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_fulfillments_order_status
    ON order_fulfillments(order_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_fulfillments_shipper_updated
    ON order_fulfillments(shipper_id, updated_at DESC);

-- PostgreSQL UNIQUE constraints treat NULLs as distinct, so the old
-- (order_id, vendor_id) constraint did not prevent two platform rows.
-- Consolidate any duplicate legacy platform rows before adding the index.
-- The temporary mapping lets us merge all item links with ON CONFLICT,
-- then remove duplicate fulfillment rows without violating the unique
-- order_item_id constraint. Keep the oldest id as the stable row referenced
-- by external history, but copy the most recently updated runtime fields to
-- it before deleting duplicates.
CREATE TEMP TABLE fulfillment_platform_duplicates ON COMMIT DROP AS
SELECT id, order_id,
       first_value(id) OVER (PARTITION BY order_id ORDER BY created_at, id) AS keep_id
FROM (
    SELECT f.*, count(*) OVER (PARTITION BY order_id) AS duplicate_count
    FROM order_fulfillments f
    WHERE vendor_id IS NULL
) platform_rows
WHERE duplicate_count > 1;

WITH latest_by_order AS (
    SELECT DISTINCT ON (order_id)
           order_id, status, shipper_id, carrier_id, tracking_number,
           commission_percent, commission_amount, delivered_at, returned_at,
           delivery_proof_url, delivery_proof_public_id, updated_at
    FROM order_fulfillments
    WHERE vendor_id IS NULL
    ORDER BY order_id, updated_at DESC NULLS LAST, created_at DESC, id DESC
), duplicate_groups AS (
    SELECT DISTINCT order_id, keep_id
    FROM fulfillment_platform_duplicates
)
UPDATE order_fulfillments keeper
SET status = latest.status,
    shipper_id = latest.shipper_id,
    carrier_id = latest.carrier_id,
    tracking_number = latest.tracking_number,
    commission_percent = latest.commission_percent,
    commission_amount = latest.commission_amount,
    delivered_at = latest.delivered_at,
    returned_at = latest.returned_at,
    delivery_proof_url = latest.delivery_proof_url,
    delivery_proof_public_id = latest.delivery_proof_public_id,
    updated_at = latest.updated_at
FROM duplicate_groups duplicates
JOIN latest_by_order latest ON latest.order_id = duplicates.order_id
WHERE keeper.id = duplicates.keep_id;

INSERT INTO order_fulfillment_items (fulfillment_id, order_item_id)
SELECT duplicate_row.keep_id, item.order_item_id
FROM fulfillment_platform_duplicates duplicate_row
JOIN order_fulfillment_items item ON item.fulfillment_id = duplicate_row.id
ON CONFLICT (order_item_id) DO NOTHING;

DELETE FROM order_fulfillment_items item
USING fulfillment_platform_duplicates duplicate_row
WHERE item.fulfillment_id = duplicate_row.id;

DELETE FROM order_fulfillments fulfillment
USING fulfillment_platform_duplicates duplicate_row
WHERE fulfillment.id = duplicate_row.id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_fulfillments_platform_order
    ON order_fulfillments(order_id) WHERE vendor_id IS NULL;

ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'FULFILLMENT_CREATED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'FULFILLMENT_ASSIGNED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'FULFILLMENT_STATUS_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'FULFILLMENT_DELIVERED';
