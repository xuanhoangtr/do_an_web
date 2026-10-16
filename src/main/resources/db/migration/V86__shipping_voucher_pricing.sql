-- Preserve existing order totals and expose the original customer-facing
-- shipping charge separately from any new shipping voucher reduction.
ALTER TABLE orders
    ADD COLUMN shipping_fee_before_discount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN shipping_discount_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN voucher_discount_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN voucher_type VARCHAR(20);

UPDATE orders
SET shipping_fee_before_discount = shipping_fee,
    shipping_discount_amount = 0,
    voucher_discount_amount = 0
WHERE shipping_fee_before_discount = 0;

ALTER TABLE orders
    ADD CONSTRAINT orders_shipping_voucher_amounts_check
    CHECK (shipping_fee_before_discount >= 0
       AND shipping_discount_amount >= 0
       AND shipping_discount_amount <= shipping_fee_before_discount
       AND voucher_discount_amount >= 0
       AND shipping_fee = shipping_fee_before_discount - shipping_discount_amount);

ALTER TABLE orders
    ADD CONSTRAINT orders_voucher_type_check
    CHECK (voucher_type IS NULL OR voucher_type IN ('PERCENT', 'FIXED', 'SHIPPING_FIXED', 'SHIPPING_FREE'));

ALTER TABLE accounting_documents
    ADD COLUMN shipping_fee_before_discount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN shipping_discount_amount BIGINT NOT NULL DEFAULT 0;

-- Accounting documents are immutable at runtime; allow this one-time schema
-- backfill so historical documents show the fee they originally recorded.
DROP TRIGGER trg_accounting_documents_append_only ON accounting_documents;
UPDATE accounting_documents
SET shipping_fee_before_discount = shipping_fee,
    shipping_discount_amount = 0
WHERE shipping_fee_before_discount = 0;
CREATE TRIGGER trg_accounting_documents_append_only
    BEFORE UPDATE OR DELETE ON accounting_documents FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

ALTER TABLE accounting_documents
    ADD CONSTRAINT accounting_documents_shipping_voucher_amounts_check
    CHECK (shipping_fee_before_discount >= 0
       AND shipping_discount_amount >= 0
       AND shipping_discount_amount <= shipping_fee_before_discount
       AND shipping_fee = shipping_fee_before_discount - shipping_discount_amount);

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS vouchers_type_check;
ALTER TABLE vouchers ADD CONSTRAINT vouchers_type_check
    CHECK (type IN ('PERCENT', 'FIXED', 'SHIPPING_FIXED', 'SHIPPING_FREE'));

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS vouchers_value_amount_check;
ALTER TABLE vouchers ADD CONSTRAINT vouchers_value_amount_check
    CHECK ((type = 'SHIPPING_FREE' AND value_amount = 0)
        OR (type <> 'SHIPPING_FREE' AND value_amount > 0));
