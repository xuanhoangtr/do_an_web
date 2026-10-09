DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE shipping_fee <> trunc(shipping_fee)
    ) THEN
        RAISE EXCEPTION
            'orders.shipping_fee contains fractional values; manual remediation required';
    END IF;
END $$;

ALTER TABLE orders
    ALTER COLUMN shipping_fee TYPE BIGINT
    USING shipping_fee::BIGINT;

ALTER TABLE orders
    ALTER COLUMN shipping_fee SET DEFAULT 0;
