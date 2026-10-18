-- Separate platform and shop voucher ownership without rewriting historical
-- order amounts. Existing vouchers remain platform-funded.
ALTER TABLE vouchers
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    ADD COLUMN shop_id UUID,
    ADD COLUMN funding_source VARCHAR(16) NOT NULL DEFAULT 'PLATFORM';

ALTER TABLE vouchers
    ADD CONSTRAINT fk_vouchers_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
    ADD CONSTRAINT vouchers_scope_funding_check CHECK (
        (scope = 'PLATFORM' AND shop_id IS NULL AND funding_source = 'PLATFORM')
        OR (scope = 'SHOP' AND shop_id IS NOT NULL AND funding_source = 'SHOP')
    ),
    ADD CONSTRAINT vouchers_shop_type_check CHECK (
        scope <> 'SHOP' OR type IN ('PERCENT', 'FIXED')
    );

CREATE INDEX idx_vouchers_shop_active_window ON vouchers(shop_id, active, starts_at, ends_at)
    WHERE scope = 'SHOP';

-- Allow one voucher per merchandise/shipping slot, while preventing the same
-- voucher from being counted twice on one order.
ALTER TABLE voucher_usages ADD COLUMN slot VARCHAR(20) NOT NULL DEFAULT 'MERCHANDISE';
UPDATE voucher_usages vu
SET slot = 'SHIPPING'
FROM vouchers v
WHERE v.id = vu.voucher_id AND v.type IN ('SHIPPING_FIXED', 'SHIPPING_FREE');

DO $$
DECLARE existing_constraint RECORD;
BEGIN
    FOR existing_constraint IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'voucher_usages'::regclass
          AND contype = 'u'
          AND pg_get_constraintdef(oid) = 'UNIQUE (order_id)'
    LOOP
        EXECUTE format('ALTER TABLE voucher_usages DROP CONSTRAINT %I', existing_constraint.conname);
    END LOOP;
END $$;

ALTER TABLE voucher_usages
    ADD CONSTRAINT voucher_usages_slot_check CHECK (slot IN ('MERCHANDISE', 'SHIPPING')),
    ADD CONSTRAINT uq_voucher_usages_order_slot UNIQUE (order_id, slot),
    ADD CONSTRAINT uq_voucher_usages_order_voucher UNIQUE (order_id, voucher_id);

-- Composite keys make it impossible to attach an allocation to an item from
-- another order or shop. Snapshot rows are append-only once inserted.
ALTER TABLE order_items
    ADD CONSTRAINT uq_order_items_id_order_shop UNIQUE (id, order_id, shop_id);

CREATE TABLE order_applied_discounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    voucher_id UUID REFERENCES vouchers(id) ON DELETE RESTRICT,
    slot VARCHAR(20) NOT NULL,
    code VARCHAR(80) NOT NULL,
    voucher_type VARCHAR(20) NOT NULL,
    voucher_scope VARCHAR(16) NOT NULL,
    funding_source VARCHAR(16) NOT NULL,
    snapshot_source VARCHAR(20) NOT NULL DEFAULT 'LIVE_USAGE',
    discount_amount BIGINT NOT NULL CHECK (discount_amount >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_order_applied_discounts_order_slot UNIQUE (order_id, slot),
    CONSTRAINT uq_order_applied_discounts_id_order_shop UNIQUE (id, order_id, shop_id),
    CONSTRAINT order_applied_discounts_slot_check CHECK (slot IN ('MERCHANDISE', 'SHIPPING')),
    CONSTRAINT order_applied_discounts_type_check CHECK (
        voucher_type IN ('PERCENT', 'FIXED', 'SHIPPING_FIXED', 'SHIPPING_FREE')
    ),
    CONSTRAINT order_applied_discounts_scope_funding_check CHECK (
        (voucher_scope = 'PLATFORM' AND funding_source = 'PLATFORM')
        OR (voucher_scope = 'SHOP' AND funding_source = 'SHOP')
    ),
    CONSTRAINT order_applied_discounts_snapshot_source_check CHECK (
        snapshot_source IN ('LIVE_USAGE', 'ORDER_SNAPSHOT')
    ),
    CONSTRAINT fk_order_applied_discounts_order_shop
        FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT
);

CREATE INDEX idx_order_applied_discounts_shop_order ON order_applied_discounts(shop_id, order_id);
CREATE INDEX idx_order_applied_discounts_voucher ON order_applied_discounts(voucher_id);

CREATE TABLE order_applied_discount_item_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_applied_discount_id UUID NOT NULL,
    order_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_discount_item_allocations_discount_item UNIQUE (order_applied_discount_id, order_item_id),
    CONSTRAINT fk_discount_item_allocations_discount_order_shop
        FOREIGN KEY (order_applied_discount_id, order_id, shop_id)
        REFERENCES order_applied_discounts(id, order_id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_discount_item_allocations_item_order_shop
        FOREIGN KEY (order_item_id, order_id, shop_id)
        REFERENCES order_items(id, order_id, shop_id) ON DELETE RESTRICT
);

CREATE OR REPLACE FUNCTION prevent_voucher_discount_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Applied discount snapshots are immutable';
END;
$$;

CREATE TRIGGER trg_order_applied_discounts_append_only
    BEFORE UPDATE OR DELETE ON order_applied_discounts
    FOR EACH ROW EXECUTE FUNCTION prevent_voucher_discount_snapshot_mutation();
CREATE TRIGGER trg_order_applied_discount_item_allocations_append_only
    BEFORE UPDATE OR DELETE ON order_applied_discount_item_allocations
    FOR EACH ROW EXECUTE FUNCTION prevent_voucher_discount_snapshot_mutation();

-- Preserve the exact value used historically; do not recalculate prices or
-- update orders during this migration.
INSERT INTO order_applied_discounts
    (order_id, shop_id, voucher_id, slot, code, voucher_type, voucher_scope, funding_source, snapshot_source,
     discount_amount, created_at)
SELECT vu.order_id, o.shop_id, v.id, vu.slot, v.code, v.type, 'PLATFORM', 'PLATFORM', 'LIVE_USAGE',
       vu.discount_amount, vu.created_at
FROM voucher_usages vu
JOIN vouchers v ON v.id = vu.voucher_id
JOIN orders o ON o.id = vu.order_id;

-- Quota release removes live usage rows. Reconstruct the immutable snapshot
-- from the order's persisted voucher fields for those historical orders.
INSERT INTO order_applied_discounts
    (order_id, shop_id, voucher_id, slot, code, voucher_type, voucher_scope, funding_source, snapshot_source,
     discount_amount, created_at)
SELECT o.id, o.shop_id, v.id,
       CASE WHEN o.voucher_type IN ('SHIPPING_FIXED', 'SHIPPING_FREE') THEN 'SHIPPING' ELSE 'MERCHANDISE' END,
       o.discount_code, o.voucher_type, 'PLATFORM', 'PLATFORM', 'ORDER_SNAPSHOT',
       CASE WHEN o.voucher_type IN ('SHIPPING_FIXED', 'SHIPPING_FREE')
            THEN o.shipping_discount_amount ELSE o.voucher_discount_amount END,
       o.created_at
FROM orders o
LEFT JOIN vouchers v ON lower(v.code) = lower(o.discount_code)
WHERE o.discount_code IS NOT NULL
  AND o.voucher_type IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM order_applied_discounts d
      WHERE d.order_id = o.id
        AND d.slot = CASE WHEN o.voucher_type IN ('SHIPPING_FIXED', 'SHIPPING_FREE')
                          THEN 'SHIPPING' ELSE 'MERCHANDISE' END
  );
