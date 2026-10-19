-- Irreversible phase 2 of the shop-only conversion.
-- This location is excluded from the application's default Flyway locations.
-- Operators must run it explicitly after backing up and reconciling phase 1.

DO $$
BEGIN
    IF lower('${shopPhase2Approved}') <> 'true' THEN
        RAISE EXCEPTION 'Shop phase 2 requires explicit approval (shopPhase2Approved=true)';
    END IF;

    IF to_regclass('public.flyway_schema_history') IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM public.flyway_schema_history
            WHERE version = '76' AND success
       ) THEN
        RAISE EXCEPTION 'Shop phase 2 requires successful default migration V76';
    END IF;

    IF EXISTS (SELECT 1 FROM products WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM catalog_products WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM order_items WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM order_fulfillments WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM vouchers WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM support_tickets WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM return_cases WHERE vendor_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM users WHERE upper(role) = 'VENDOR')
       OR EXISTS (
            SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
            WHERE upper(r.name) = 'VENDOR'
       ) THEN
        RAISE EXCEPTION 'Shop phase 2 found unresolved operational vendor links';
    END IF;
END $$;

-- Keep a transactional before-image of all shop data that must survive phase 2.
CREATE TEMP TABLE shop_phase2_preserved_data ON COMMIT DROP AS
SELECT
    (SELECT count(*) FROM users) AS users_count,
    (SELECT count(*) FROM products) AS products_count,
    (SELECT count(*) FROM catalog_products) AS catalog_products_count,
    (SELECT count(*) FROM orders) AS orders_count,
    (SELECT COALESCE(sum(total_amount), 0)::numeric FROM orders) AS orders_total,
    (SELECT count(*) FROM order_items) AS order_items_count,
    (SELECT COALESCE(sum(line_total), 0)::numeric FROM order_items) AS order_items_total,
    (SELECT count(*) FROM payments) AS payments_count,
    (SELECT COALESCE(sum(amount), 0)::numeric FROM payments) AS payments_total,
    (SELECT count(*) FROM order_fulfillments) AS fulfillments_count,
    (SELECT count(*) FROM order_fulfillment_items) AS fulfillment_items_count,
    (SELECT count(*) FROM support_tickets) AS support_tickets_count,
    (SELECT count(*) FROM return_cases) AS return_cases_count,
    (SELECT count(*) FROM return_shipments) AS return_shipments_count,
    (SELECT count(*) FROM accounting_documents) AS accounting_documents_count,
    (SELECT COALESCE(sum(total_amount), 0)::numeric FROM accounting_documents) AS accounting_documents_total,
    (SELECT count(*) FROM accounting_document_sellers) AS accounting_sellers_count,
    (SELECT count(*) FROM accounting_document_lines) AS accounting_lines_count;

-- Remove objects that still execute vendor-only logic before removing their tables.
DROP TRIGGER IF EXISTS trg_catalog_products_approved_vendor ON catalog_products;
DROP TRIGGER IF EXISTS trg_vendor_status_deactivate_products ON vendor_profiles;
DROP TRIGGER IF EXISTS trg_settlement_entry_money_immutable ON vendor_settlement_entries;
DROP FUNCTION IF EXISTS enforce_approved_vendor_product();
DROP FUNCTION IF EXISTS deactivate_products_for_unapproved_vendor();
DROP FUNCTION IF EXISTS protect_settlement_entry_money();

-- No CASCADE: unexpected dependencies must stop the migration for review.
DROP TABLE IF EXISTS payout_operations;
DROP TABLE IF EXISTS finance_holds;
DROP TABLE IF EXISTS vendor_payouts;
DROP TABLE IF EXISTS vendor_payout_destinations;
DROP TABLE IF EXISTS vendor_settlement_entries;
DROP TABLE IF EXISTS settlement_periods;
DROP TABLE IF EXISTS commission_configs;
DROP TABLE IF EXISTS vendor_status_history;
DROP TABLE IF EXISTS vendor_profiles;

-- Historical seller snapshots and accounting line references remain immutable.
ALTER TABLE products DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE catalog_products DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE order_items DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE order_fulfillments DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE vouchers DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE support_tickets DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE return_cases DROP COLUMN IF EXISTS vendor_id;
ALTER TABLE accounting_document_sellers DROP COLUMN IF EXISTS vendor_id;

-- V76 removed all role links. Keep users and their order history; remove only
-- the now-orphaned permission catalog entry.
DELETE FROM roles WHERE upper(name) = 'VENDOR';

DO $$
DECLARE
    before_row RECORD;
BEGIN
    SELECT * INTO STRICT before_row FROM shop_phase2_preserved_data;

    IF before_row.users_count IS DISTINCT FROM (SELECT count(*) FROM users)
       OR before_row.products_count IS DISTINCT FROM (SELECT count(*) FROM products)
       OR before_row.catalog_products_count IS DISTINCT FROM (SELECT count(*) FROM catalog_products)
       OR before_row.orders_count IS DISTINCT FROM (SELECT count(*) FROM orders)
       OR before_row.orders_total IS DISTINCT FROM (SELECT COALESCE(sum(total_amount), 0)::numeric FROM orders)
       OR before_row.order_items_count IS DISTINCT FROM (SELECT count(*) FROM order_items)
       OR before_row.order_items_total IS DISTINCT FROM (SELECT COALESCE(sum(line_total), 0)::numeric FROM order_items)
       OR before_row.payments_count IS DISTINCT FROM (SELECT count(*) FROM payments)
       OR before_row.payments_total IS DISTINCT FROM (SELECT COALESCE(sum(amount), 0)::numeric FROM payments)
       OR before_row.fulfillments_count IS DISTINCT FROM (SELECT count(*) FROM order_fulfillments)
       OR before_row.fulfillment_items_count IS DISTINCT FROM (SELECT count(*) FROM order_fulfillment_items)
       OR before_row.support_tickets_count IS DISTINCT FROM (SELECT count(*) FROM support_tickets)
       OR before_row.return_cases_count IS DISTINCT FROM (SELECT count(*) FROM return_cases)
       OR before_row.return_shipments_count IS DISTINCT FROM (SELECT count(*) FROM return_shipments)
       OR before_row.accounting_documents_count IS DISTINCT FROM (SELECT count(*) FROM accounting_documents)
       OR before_row.accounting_documents_total IS DISTINCT FROM
            (SELECT COALESCE(sum(total_amount), 0)::numeric FROM accounting_documents)
       OR before_row.accounting_sellers_count IS DISTINCT FROM (SELECT count(*) FROM accounting_document_sellers)
       OR before_row.accounting_lines_count IS DISTINCT FROM (SELECT count(*) FROM accounting_document_lines) THEN
        RAISE EXCEPTION 'Shop phase 2 changed preserved shop, order, payment, return, support, or accounting data';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND column_name = 'vendor_id'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name IN (
            'commission_configs', 'finance_holds', 'payout_operations',
            'settlement_periods', 'vendor_payout_destinations', 'vendor_payouts',
            'vendor_profiles', 'vendor_settlement_entries', 'vendor_status_history'
        )
    ) OR EXISTS (
        SELECT 1 FROM roles WHERE upper(name) = 'VENDOR'
    ) OR EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE NOT tgisinternal AND tgname IN (
            'trg_catalog_products_approved_vendor', 'trg_vendor_status_deactivate_products'
        )
    ) THEN
        RAISE EXCEPTION 'Shop phase 2 left vendor-only schema objects behind';
    END IF;
END $$;
