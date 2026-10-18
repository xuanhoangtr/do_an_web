-- Introduce shop ownership for existing platform marketplace records.
-- The fixed id is a stable system identity shared with PlatformShopDefaults.
-- This migration only adds ownership metadata; it does not rewrite business
-- state, money, inventory, tracking, or accounting snapshots.

INSERT INTO shops (id, code, slug, display_name, kind, status)
VALUES ('00000000-0000-4000-8000-000000000001', 'UTEWEDDING', 'utewedding', 'UTEWedding', 'PLATFORM', 'ACTIVE');

INSERT INTO shop_status_history (shop_id, previous_status, new_status, reason)
VALUES ('00000000-0000-4000-8000-000000000001', NULL, 'ACTIVE', 'Khởi tạo shop nền tảng khi bổ sung ownership.');

ALTER TABLE catalog_products ADD COLUMN shop_id UUID;
ALTER TABLE orders ADD COLUMN shop_id UUID;
ALTER TABLE orders ADD COLUMN shop_name_snapshot VARCHAR(160);
ALTER TABLE order_items ADD COLUMN shop_id UUID;
ALTER TABLE order_fulfillments ADD COLUMN shop_id UUID;
ALTER TABLE order_fulfillment_items ADD COLUMN shop_id UUID;
ALTER TABLE return_cases ADD COLUMN shop_id UUID;
ALTER TABLE return_case_items ADD COLUMN shop_id UUID;
ALTER TABLE return_shipments ADD COLUMN shop_id UUID;
-- Use a constant default for this append-only snapshot table. PostgreSQL applies
-- the metadata default to existing rows without issuing UPDATE statements that
-- would trip its immutability trigger.
ALTER TABLE order_shipping_snapshots ADD COLUMN shop_id UUID
    DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE fulfillment_shipments ADD COLUMN shop_id UUID;
ALTER TABLE order_carrier_costs ADD COLUMN shop_id UUID;
ALTER TABLE shipping_provider_operations ADD COLUMN shop_id UUID;

UPDATE catalog_products
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE orders
SET shop_id = '00000000-0000-4000-8000-000000000001',
    shop_name_snapshot = 'UTEWedding'
WHERE shop_id IS NULL;

UPDATE order_items
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE order_fulfillments
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE order_fulfillment_items
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE return_cases
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE return_case_items
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE return_shipments
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE order_shipping_snapshots
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE fulfillment_shipments
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE order_carrier_costs
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

UPDATE shipping_provider_operations
SET shop_id = '00000000-0000-4000-8000-000000000001'
WHERE shop_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_products WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM orders WHERE shop_id IS NULL OR shop_name_snapshot IS NULL)
       OR EXISTS (SELECT 1 FROM order_items WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM order_fulfillments WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM order_fulfillment_items WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM return_cases WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM return_case_items WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM return_shipments WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM order_shipping_snapshots WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM fulfillment_shipments WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM order_carrier_costs WHERE shop_id IS NULL)
       OR EXISTS (SELECT 1 FROM shipping_provider_operations WHERE shop_id IS NULL) THEN
        RAISE EXCEPTION 'Shop ownership backfill left null records';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        JOIN catalog_products p ON p.id = oi.product_id
        WHERE oi.shop_id <> o.shop_id OR oi.shop_id <> p.shop_id
    ) THEN
        RAISE EXCEPTION 'Order item ownership does not match its order and product';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM order_fulfillments f
        JOIN orders o ON o.id = f.order_id
        WHERE f.shop_id <> o.shop_id
    ) THEN
        RAISE EXCEPTION 'Fulfillment ownership does not match its order';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM order_fulfillment_items fi
        JOIN order_fulfillments f ON f.id = fi.fulfillment_id
        JOIN order_items oi ON oi.id = fi.order_item_id
        WHERE fi.shop_id <> f.shop_id OR fi.shop_id <> oi.shop_id
    ) THEN
        RAISE EXCEPTION 'Fulfillment item ownership does not match its parent rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM return_cases rc
        JOIN orders o ON o.id = rc.order_id
        JOIN order_fulfillments f ON f.id = rc.fulfillment_id
        WHERE rc.shop_id <> o.shop_id OR rc.shop_id <> f.shop_id
    ) THEN
        RAISE EXCEPTION 'Return case ownership does not match its order and fulfillment';
    END IF;

    IF EXISTS (
        SELECT 1 FROM return_case_items ri
        JOIN return_cases rc ON rc.id = ri.return_case_id
        JOIN order_items oi ON oi.id = ri.order_item_id
        WHERE ri.shop_id <> rc.shop_id OR ri.shop_id <> oi.shop_id
    ) THEN
        RAISE EXCEPTION 'Return item ownership does not match its case and order item';
    END IF;

    IF EXISTS (
        SELECT 1 FROM return_shipments rs
        JOIN return_cases rc ON rc.id = rs.return_case_id
        WHERE rs.shop_id <> rc.shop_id
    ) OR EXISTS (
        SELECT 1 FROM order_shipping_snapshots ss
        JOIN orders o ON o.id = ss.order_id
        WHERE ss.shop_id <> o.shop_id
    ) OR EXISTS (
        SELECT 1 FROM fulfillment_shipments fs
        JOIN order_fulfillments f ON f.id = fs.fulfillment_id
        WHERE fs.shop_id <> f.shop_id
    ) OR EXISTS (
        SELECT 1 FROM order_carrier_costs cc
        JOIN orders o ON o.id = cc.order_id
        WHERE cc.shop_id <> o.shop_id
    ) OR EXISTS (
        SELECT 1 FROM shipping_provider_operations op
        JOIN fulfillment_shipments fs ON fs.id = op.shipment_id
        WHERE op.shop_id <> fs.shop_id
    ) THEN
        RAISE EXCEPTION 'Shipping snapshot ownership does not match its parent';
    END IF;
END $$;

ALTER TABLE catalog_products ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE catalog_products ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE orders ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN shop_name_snapshot SET DEFAULT 'UTEWedding';
ALTER TABLE orders ALTER COLUMN shop_name_snapshot SET NOT NULL;
ALTER TABLE order_items ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE order_items ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE order_fulfillments ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE order_fulfillments ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE order_fulfillment_items ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE order_fulfillment_items ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE return_cases ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE return_cases ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE return_case_items ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE return_case_items ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE return_shipments ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE return_shipments ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE order_shipping_snapshots ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE order_shipping_snapshots ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE fulfillment_shipments ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE fulfillment_shipments ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE order_carrier_costs ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE order_carrier_costs ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE shipping_provider_operations ALTER COLUMN shop_id SET DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE shipping_provider_operations ALTER COLUMN shop_id SET NOT NULL;

ALTER TABLE catalog_products ADD CONSTRAINT fk_catalog_products_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE orders ADD CONSTRAINT fk_orders_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE order_fulfillments ADD CONSTRAINT fk_order_fulfillments_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE order_fulfillment_items ADD CONSTRAINT fk_order_fulfillment_items_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE return_cases ADD CONSTRAINT fk_return_cases_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE return_case_items ADD CONSTRAINT fk_return_case_items_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE return_shipments ADD CONSTRAINT fk_return_shipments_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE order_shipping_snapshots ADD CONSTRAINT fk_order_shipping_snapshots_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE fulfillment_shipments ADD CONSTRAINT fk_fulfillment_shipments_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE order_carrier_costs ADD CONSTRAINT fk_order_carrier_costs_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;
ALTER TABLE shipping_provider_operations ADD CONSTRAINT fk_shipping_provider_operations_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT;

ALTER TABLE catalog_products ADD CONSTRAINT uq_catalog_products_id_shop UNIQUE (id, shop_id);
ALTER TABLE orders ADD CONSTRAINT uq_orders_id_shop UNIQUE (id, shop_id);
ALTER TABLE order_items ADD CONSTRAINT uq_order_items_id_shop UNIQUE (id, shop_id);
ALTER TABLE order_fulfillments ADD CONSTRAINT uq_order_fulfillments_id_shop UNIQUE (id, shop_id);
ALTER TABLE return_cases ADD CONSTRAINT uq_return_cases_id_shop UNIQUE (id, shop_id);
ALTER TABLE fulfillment_shipments ADD CONSTRAINT uq_fulfillment_shipments_id_shop UNIQUE (id, shop_id);

ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order_shop
    FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE CASCADE;
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_product_shop
    FOREIGN KEY (product_id, shop_id) REFERENCES catalog_products(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE order_fulfillments ADD CONSTRAINT fk_order_fulfillments_order_shop
    FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE CASCADE;
ALTER TABLE order_fulfillment_items ADD CONSTRAINT fk_fulfillment_items_fulfillment_shop
    FOREIGN KEY (fulfillment_id, shop_id) REFERENCES order_fulfillments(id, shop_id) ON DELETE CASCADE;
ALTER TABLE order_fulfillment_items ADD CONSTRAINT fk_fulfillment_items_order_item_shop
    FOREIGN KEY (order_item_id, shop_id) REFERENCES order_items(id, shop_id) ON DELETE CASCADE;
ALTER TABLE return_cases ADD CONSTRAINT fk_return_cases_order_shop
    FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE return_cases ADD CONSTRAINT fk_return_cases_fulfillment_shop
    FOREIGN KEY (fulfillment_id, shop_id) REFERENCES order_fulfillments(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE return_case_items ADD CONSTRAINT fk_return_case_items_case_shop
    FOREIGN KEY (return_case_id, shop_id) REFERENCES return_cases(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE return_case_items ADD CONSTRAINT fk_return_case_items_order_item_shop
    FOREIGN KEY (order_item_id, shop_id) REFERENCES order_items(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE return_shipments ADD CONSTRAINT fk_return_shipments_case_shop
    FOREIGN KEY (return_case_id, shop_id) REFERENCES return_cases(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE order_shipping_snapshots ADD CONSTRAINT fk_shipping_snapshots_order_shop
    FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE fulfillment_shipments ADD CONSTRAINT fk_shipments_fulfillment_shop
    FOREIGN KEY (fulfillment_id, shop_id) REFERENCES order_fulfillments(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE order_carrier_costs ADD CONSTRAINT fk_order_carrier_costs_order_shop
    FOREIGN KEY (order_id, shop_id) REFERENCES orders(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE shipping_provider_operations ADD CONSTRAINT fk_provider_operations_shipment_shop
    FOREIGN KEY (shipment_id, shop_id) REFERENCES fulfillment_shipments(id, shop_id) ON DELETE RESTRICT;

CREATE INDEX idx_catalog_products_shop_active_created ON catalog_products(shop_id, active, created_at DESC);
CREATE INDEX idx_orders_shop_status_created ON orders(shop_id, status, created_at DESC);
CREATE INDEX idx_order_items_shop_order ON order_items(shop_id, order_id);
CREATE INDEX idx_fulfillments_shop_status_created ON order_fulfillments(shop_id, status, created_at DESC);
CREATE INDEX idx_return_cases_shop_status_created ON return_cases(shop_id, status, created_at DESC);
CREATE INDEX idx_provider_operations_shop_created ON shipping_provider_operations(shop_id, created_at DESC);
