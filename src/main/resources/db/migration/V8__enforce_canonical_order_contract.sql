-- Enforce the complete order/fulfillment lifecycle after the earlier
-- transitional migrations. SHIPPED remains readable for legacy rows; new
-- operations use SHIPPING.
DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'orders'::regclass
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check_lifecycle_final
    CHECK (status IN ('PENDING','PAID','CONFIRMED','PROCESSING','SHIPPING',
                      'SHIPPED','DELIVERED','CANCELLED','EXPIRED','REFUNDED','RETURNED'));

COMMENT ON COLUMN orders.shipper_id IS
    'Assigned delivery operator. NULL means the order is not claimed.';
COMMENT ON COLUMN orders.tracking_number IS
    'Carrier reference once fulfillment begins.';
