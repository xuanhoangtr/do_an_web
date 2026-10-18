-- Phase 1 of the shop-only conversion. Keep every catalog, order, fulfillment,
-- payment, return and support row; clear only the obsolete owner dimension.
-- Vendor-only tables and columns are intentionally retained until operators
-- reconcile the migrated production data and approve the separate phase-2 drop.

-- A shop may have multiple historical parcels for one order. Preserve their
-- stable IDs, statuses, tracking details and item links instead of merging them.
DROP INDEX IF EXISTS uk_fulfillments_platform_order;

UPDATE products SET vendor_id = NULL WHERE vendor_id IS NOT NULL;
UPDATE catalog_products SET vendor_id = NULL WHERE vendor_id IS NOT NULL;
UPDATE order_items SET vendor_id = NULL WHERE vendor_id IS NOT NULL;
UPDATE order_fulfillments SET vendor_id = NULL WHERE vendor_id IS NOT NULL;
UPDATE vouchers SET vendor_id = NULL WHERE vendor_id IS NOT NULL;
UPDATE support_tickets SET vendor_id = NULL WHERE vendor_id IS NOT NULL;

ALTER TABLE return_cases ALTER COLUMN vendor_id DROP NOT NULL;
UPDATE return_cases SET vendor_id = NULL WHERE vendor_id IS NOT NULL;

-- Return disputes remain attached to customer cases and no longer create
-- vendor finance holds. Rename shipment directions without replacing rows.
ALTER TABLE return_shipments DROP CONSTRAINT IF EXISTS return_shipments_direction_check;
UPDATE return_shipments SET direction = 'CUSTOMER_TO_SHOP' WHERE direction = 'CUSTOMER_TO_VENDOR';
UPDATE return_shipments SET direction = 'SHOP_TO_CUSTOMER' WHERE direction = 'VENDOR_TO_CUSTOMER';
ALTER TABLE return_shipments ADD CONSTRAINT return_shipments_direction_shop_check
    CHECK (direction IN ('CUSTOMER_TO_SHOP','SHOP_TO_CUSTOMER'));

-- VENDOR was already demoted by V49. Remove any remaining legacy RBAC links
-- while preserving all user accounts and their canonical roles.
DELETE FROM user_roles ur USING roles r
WHERE ur.role_id = r.id AND upper(r.name) = 'VENDOR';

-- Legacy role links can outlive deleted role catalog entries. Such rows cannot
-- grant an authority; remove only the orphaned links, never the user accounts.
DELETE FROM user_roles ur
WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.id = ur.role_id);

ALTER TABLE user_roles VALIDATE CONSTRAINT user_roles_role_id_fkey;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM products WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM catalog_products WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM order_items WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM order_fulfillments WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM vouchers WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM support_tickets WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM return_cases WHERE vendor_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Vendor-to-shop conversion left linked operational rows';
    END IF;

    IF EXISTS (
        SELECT 1 FROM user_roles ur
        WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.id = ur.role_id)
    ) THEN
        RAISE EXCEPTION 'Vendor-to-shop conversion left orphaned role links';
    END IF;
END $$;
