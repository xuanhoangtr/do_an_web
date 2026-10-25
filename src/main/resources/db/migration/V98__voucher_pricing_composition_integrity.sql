-- V94 already introduced independent voucher usage slots and append-only
-- discount/allocation snapshots. Tighten the invariants used by the two-slot
-- checkout without rewriting historical order amounts or legacy snapshots.

ALTER TABLE order_applied_discounts
    ADD CONSTRAINT order_applied_discounts_slot_type_check CHECK (
        (slot = 'MERCHANDISE' AND voucher_type IN ('PERCENT', 'FIXED'))
        OR (slot = 'SHIPPING'
            AND voucher_type IN ('SHIPPING_FIXED', 'SHIPPING_FREE')
            AND voucher_scope = 'PLATFORM'
            AND funding_source = 'PLATFORM')
    );

CREATE OR REPLACE FUNCTION ensure_discount_allocation_is_merchandise()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_slot VARCHAR(20);
BEGIN
    SELECT slot INTO parent_slot
    FROM order_applied_discounts
    WHERE id = NEW.order_applied_discount_id
      AND order_id = NEW.order_id
      AND shop_id = NEW.shop_id;

    IF parent_slot IS NULL OR parent_slot <> 'MERCHANDISE' THEN
        RAISE EXCEPTION 'Item allocations require a merchandise discount snapshot';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_discount_item_allocation_merchandise_only
    BEFORE INSERT ON order_applied_discount_item_allocations
    FOR EACH ROW EXECUTE FUNCTION ensure_discount_allocation_is_merchandise();

CREATE OR REPLACE FUNCTION verify_discount_item_allocation_total()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_discount_id UUID;
        target_slot VARCHAR(20);
        expected_amount BIGINT;
        allocated_amount BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'order_applied_discounts' THEN
        target_discount_id := NEW.id;
    ELSE
        target_discount_id := NEW.order_applied_discount_id;
    END IF;

    SELECT slot, discount_amount INTO target_slot, expected_amount
    FROM order_applied_discounts WHERE id = target_discount_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(amount), 0) INTO allocated_amount
    FROM order_applied_discount_item_allocations
    WHERE order_applied_discount_id = target_discount_id;

    IF target_slot = 'SHIPPING' AND allocated_amount <> 0 THEN
        RAISE EXCEPTION 'Shipping discounts cannot be allocated to merchandise items';
    END IF;
    IF target_slot = 'MERCHANDISE' AND allocated_amount <> expected_amount THEN
        RAISE EXCEPTION 'Merchandise discount allocations must equal the applied discount amount';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_applied_discount_allocation_total
    AFTER INSERT ON order_applied_discounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_discount_item_allocation_total();

CREATE CONSTRAINT TRIGGER trg_item_allocation_total
    AFTER INSERT ON order_applied_discount_item_allocations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_discount_item_allocation_total();
