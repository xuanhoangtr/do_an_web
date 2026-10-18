-- Shop ownership is historical transaction identity. Keep the backfilled owner
-- and order display-name snapshot stable after creation.
CREATE OR REPLACE FUNCTION prevent_shop_ownership_reassignment()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.shop_id IS DISTINCT FROM NEW.shop_id THEN
        RAISE EXCEPTION 'Shop ownership is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_catalog_products_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON catalog_products
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_order_items_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON order_items
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_order_fulfillments_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON order_fulfillments
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_order_fulfillment_items_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON order_fulfillment_items
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_return_cases_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON return_cases
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_return_case_items_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON return_case_items
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_return_shipments_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON return_shipments
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_order_shipping_snapshots_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON order_shipping_snapshots
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_fulfillment_shipments_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON fulfillment_shipments
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_order_carrier_costs_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON order_carrier_costs
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();
CREATE TRIGGER trg_shipping_provider_operations_shop_ownership_immutable
    BEFORE UPDATE OF shop_id ON shipping_provider_operations
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_ownership_reassignment();

CREATE OR REPLACE FUNCTION prevent_order_shop_snapshot_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.shop_id IS DISTINCT FROM NEW.shop_id
       OR OLD.shop_name_snapshot IS DISTINCT FROM NEW.shop_name_snapshot THEN
        RAISE EXCEPTION 'Order shop ownership snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_orders_shop_ownership_snapshot_immutable
    BEFORE UPDATE OF shop_id, shop_name_snapshot ON orders
    FOR EACH ROW EXECUTE FUNCTION prevent_order_shop_snapshot_rewrite();
