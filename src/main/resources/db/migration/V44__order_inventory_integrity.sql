-- Checkout idempotency must remember the payload that claimed the key, and
-- catalog stock cannot be driven below zero by any writer.
ALTER TABLE order_idempotency_keys
    ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

ALTER TABLE order_idempotency_keys
    DROP CONSTRAINT IF EXISTS order_idempotency_request_hash_hex;
ALTER TABLE order_idempotency_keys
    ADD CONSTRAINT order_idempotency_request_hash_hex
    CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN order_idempotency_keys.request_hash IS
    'SHA-256 hex of the canonical checkout payload. A reused key with a different hash is rejected. NULL is retained only for rows created before this constraint.';

CREATE OR REPLACE FUNCTION order_idempotency_keys_require_request_hash()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.request_hash IS NULL THEN
        RAISE EXCEPTION 'order_idempotency_keys.request_hash is required';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_order_idempotency_keys_require_request_hash ON order_idempotency_keys;
CREATE TRIGGER trg_order_idempotency_keys_require_request_hash
    BEFORE INSERT ON order_idempotency_keys
    FOR EACH ROW
    EXECUTE FUNCTION order_idempotency_keys_require_request_hash();

DO $$
BEGIN
    IF to_regclass('public.catalog_products') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM catalog_products WHERE stock_quantity < 0) THEN
            RAISE EXCEPTION 'catalog_products.stock_quantity contains negative values';
        END IF;
        ALTER TABLE catalog_products
            DROP CONSTRAINT IF EXISTS catalog_products_stock_non_negative;
        ALTER TABLE catalog_products
            ADD CONSTRAINT catalog_products_stock_non_negative
            CHECK (stock_quantity >= 0);
    END IF;

    IF to_regclass('public.products') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM products WHERE stock_quantity < 0) THEN
            RAISE EXCEPTION 'products.stock_quantity contains negative values';
        END IF;
        ALTER TABLE products
            DROP CONSTRAINT IF EXISTS products_stock_non_negative;
        ALTER TABLE products
            ADD CONSTRAINT products_stock_non_negative
            CHECK (stock_quantity >= 0);
    END IF;
END $$;
