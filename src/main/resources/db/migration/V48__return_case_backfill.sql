-- Backfill immutable item money fields before enabling automated RMA refunds.
UPDATE order_items item
SET voucher_discount_allocated = 0,
    net_paid_amount = GREATEST(0, item.line_total)
WHERE item.net_paid_amount = 0;

-- Existing partial refunds have no item allocation. They remain legacy-unallocated;
-- this marker intentionally prevents automated RMA reconciliation from guessing.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS legacy_unallocated_refund BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE payments
SET legacy_unallocated_refund = TRUE
WHERE refunded_amount > 0
  AND NOT EXISTS (
      SELECT 1 FROM return_resolutions resolution
      JOIN payment_provider_transactions transaction_row
        ON transaction_row.return_resolution_id = resolution.id
      WHERE transaction_row.payment_id = payments.id
  );

ALTER TABLE order_items VALIDATE CONSTRAINT order_items_return_quantity_check;
ALTER TABLE orders VALIDATE CONSTRAINT orders_shipping_refunded_amount_check;