ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'COD_COLLECTOR_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SETTLEMENT_STATUS_CHANGED';

ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(500);

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'order_fulfillments'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE order_fulfillments DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE order_fulfillments
    ADD CONSTRAINT order_fulfillments_status_check
    CHECK (status IN ('CONFIRMED','PROCESSING','SHIPPING','DELIVERED','DELIVERY_FAILED','RETURNING','RETURNED','CANCELLED'));

ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS collects_cod BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS cod_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS inventory_restored BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_fulfillments_one_cod_collector
    ON order_fulfillments(order_id)
    WHERE collects_cod = TRUE;

WITH ranked AS (
    SELECT f.id,
           o.total_amount,
           row_number() OVER (PARTITION BY f.order_id ORDER BY f.created_at, f.id) AS rn
    FROM order_fulfillments f
    JOIN orders o ON o.id = f.order_id
    WHERE upper(o.payment_method) = 'COD'
      AND o.status NOT IN ('CANCELLED', 'EXPIRED', 'REFUNDED')
      AND NOT EXISTS (
          SELECT 1 FROM order_fulfillments existing
          WHERE existing.order_id = f.order_id AND existing.collects_cod = TRUE
      )
)
UPDATE order_fulfillments fulfillment
SET collects_cod = ranked.rn = 1,
    cod_amount = CASE WHEN ranked.rn = 1 THEN ranked.total_amount ELSE 0 END
FROM ranked
WHERE fulfillment.id = ranked.id;

CREATE TABLE IF NOT EXISTS delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id UUID NOT NULL REFERENCES order_fulfillments(id) ON DELETE CASCADE,
    shipper_id UUID NOT NULL REFERENCES users(id),
    outcome VARCHAR(30) NOT NULL,
    reason_code VARCHAR(40),
    note VARCHAR(1000),
    attempted_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ(6),
    proof_url VARCHAR(2048),
    proof_public_id VARCHAR(512),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_delivery_attempts_fulfillment
    ON delivery_attempts(fulfillment_id, attempted_at);
CREATE INDEX IF NOT EXISTS idx_delivery_attempts_shipper
    ON delivery_attempts(shipper_id, attempted_at);

CREATE TABLE IF NOT EXISTS user_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    target_url VARCHAR(500),
    read_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_notifications_recipient_created
    ON user_notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_notifications_unread
    ON user_notifications(recipient_id)
    WHERE read_at IS NULL;

CREATE TABLE IF NOT EXISTS vendor_settlement_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id UUID NOT NULL REFERENCES order_fulfillments(id),
    order_id UUID NOT NULL REFERENCES orders(id),
    vendor_id UUID REFERENCES users(id),
    entry_type VARCHAR(20) NOT NULL,
    gross_amount BIGINT NOT NULL,
    commission_amount BIGINT NOT NULL,
    vendor_net BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_reference VARCHAR(190),
    adjusts_entry_id UUID,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    eligible_at TIMESTAMPTZ(6),
    paid_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_settlement_vendor_status
    ON vendor_settlement_entries(vendor_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_settlement_order ON vendor_settlement_entries(order_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_settlement_original_fulfillment
    ON vendor_settlement_entries(fulfillment_id)
    WHERE entry_type = 'ORIGINAL';

INSERT INTO vendor_settlement_entries (
    id, fulfillment_id, order_id, vendor_id, entry_type,
    gross_amount, commission_amount, vendor_net, status,
    created_at, updated_at, eligible_at
)
SELECT gen_random_uuid(),
       fulfillment.id,
       fulfillment.order_id,
       fulfillment.vendor_id,
       'ORIGINAL',
       COALESCE(items.gross_amount, 0),
       fulfillment.commission_amount,
       GREATEST(COALESCE(items.gross_amount, 0) - fulfillment.commission_amount, 0),
       'ELIGIBLE',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM order_fulfillments fulfillment
JOIN orders ord ON ord.id = fulfillment.order_id
LEFT JOIN (
    SELECT link.fulfillment_id, SUM(item.line_total) AS gross_amount
    FROM order_fulfillment_items link
    JOIN order_items item ON item.id = link.order_item_id
    GROUP BY link.fulfillment_id
) items ON items.fulfillment_id = fulfillment.id
WHERE fulfillment.vendor_id IS NOT NULL
  AND fulfillment.status = 'DELIVERED'
  AND ord.payment_status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1 FROM vendor_settlement_entries existing
      WHERE existing.fulfillment_id = fulfillment.id
        AND existing.entry_type = 'ORIGINAL'
  );
