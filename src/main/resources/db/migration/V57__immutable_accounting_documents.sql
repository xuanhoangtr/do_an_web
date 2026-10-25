-- Immutable order confirmation/payment receipt/credit-note snapshots.
CREATE TABLE accounting_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    document_number VARCHAR(60) NOT NULL UNIQUE,
    document_type VARCHAR(30) NOT NULL CHECK (document_type IN ('ORDER_CONFIRMATION','PAYMENT_RECEIPT','CREDIT_NOTE')),
    source_quality VARCHAR(40) NOT NULL DEFAULT 'LIVE_SNAPSHOT' CHECK (source_quality IN (
        'LIVE_SNAPSHOT','BACKFILLED_CURRENT_PROFILE')),
    buyer_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    subtotal BIGINT NOT NULL,
    product_discount BIGINT NOT NULL DEFAULT 0,
    voucher_discount BIGINT NOT NULL DEFAULT 0,
    shipping_fee BIGINT NOT NULL DEFAULT 0,
    total_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    payment_method VARCHAR(40),
    payment_reference VARCHAR(190),
    issued_at TIMESTAMPTZ(6) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounting_document_money_check CHECK (
        subtotal >= 0 AND product_discount >= 0 AND voucher_discount >= 0
        AND shipping_fee >= 0 AND total_amount >= 0),
    UNIQUE (order_id, document_type, payment_reference)
);
CREATE INDEX idx_accounting_documents_order ON accounting_documents(order_id, issued_at DESC);
CREATE UNIQUE INDEX uq_accounting_order_confirmation ON accounting_documents(order_id)
    WHERE document_type='ORDER_CONFIRMATION';
CREATE UNIQUE INDEX uq_accounting_payment_receipt ON accounting_documents(order_id)
    WHERE document_type='PAYMENT_RECEIPT';

CREATE TABLE accounting_document_sellers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES accounting_documents(id) ON DELETE RESTRICT,
    vendor_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    seller_snapshot JSONB NOT NULL,
    UNIQUE (document_id, vendor_id)
);
CREATE INDEX idx_accounting_sellers_document ON accounting_document_sellers(document_id);
CREATE INDEX idx_accounting_sellers_vendor ON accounting_document_sellers(vendor_id, document_id)
    WHERE vendor_id IS NOT NULL;
CREATE UNIQUE INDEX uq_accounting_platform_seller ON accounting_document_sellers(document_id)
    WHERE vendor_id IS NULL;

CREATE TABLE accounting_document_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES accounting_documents(id) ON DELETE RESTRICT,
    seller_id UUID REFERENCES accounting_document_sellers(id) ON DELETE RESTRICT,
    order_item_id UUID REFERENCES order_items(id) ON DELETE RESTRICT,
    product_id UUID,
    product_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price BIGINT NOT NULL CHECK (unit_price >= 0),
    product_discount BIGINT NOT NULL DEFAULT 0 CHECK (product_discount >= 0),
    voucher_discount BIGINT NOT NULL DEFAULT 0 CHECK (voucher_discount >= 0),
    line_total BIGINT NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX idx_accounting_lines_document ON accounting_document_lines(document_id, id);
CREATE INDEX idx_accounting_lines_seller ON accounting_document_lines(seller_id) WHERE seller_id IS NOT NULL;
CREATE INDEX idx_accounting_lines_order_item ON accounting_document_lines(order_item_id) WHERE order_item_id IS NOT NULL;

INSERT INTO accounting_documents (
    order_id, document_number, document_type, source_quality, buyer_snapshot,
    subtotal, product_discount, voucher_discount, shipping_fee, total_amount,
    currency, payment_method, payment_reference, issued_at
)
SELECT o.id, 'BACKFILL-' || o.order_number, 'ORDER_CONFIRMATION', 'BACKFILLED_CURRENT_PROFILE',
       jsonb_build_object('recipientName', o.shipping_recipient_name, 'phone', o.shipping_phone,
           'address', o.shipping_address, 'ward', o.shipping_ward, 'district', o.shipping_district,
           'province', o.shipping_province),
       o.subtotal,
       COALESCE((SELECT SUM(i.discount_amount) FROM order_items i WHERE i.order_id=o.id),0),
       o.discount_amount, o.shipping_fee, o.total_amount, o.currency, o.payment_method, NULL, o.created_at
FROM orders o
ON CONFLICT DO NOTHING;

INSERT INTO accounting_document_sellers(document_id, vendor_id, seller_snapshot)
SELECT d.id, i.vendor_id,
       jsonb_build_object('vendorId', i.vendor_id, 'shopName', COALESCE(v.shop_name, 'UTEWedding'),
           'legalName', v.legal_name, 'taxCode', v.tax_code, 'businessAddress', v.business_address,
           'snapshotSource', 'BACKFILLED_CURRENT_PROFILE')
FROM accounting_documents d
JOIN order_items i ON i.order_id=d.order_id
LEFT JOIN vendor_profiles v ON v.user_id=i.vendor_id
GROUP BY d.id, i.vendor_id, v.shop_name, v.legal_name, v.tax_code, v.business_address
ON CONFLICT DO NOTHING;

INSERT INTO accounting_document_lines (
    document_id, seller_id, order_item_id, product_id, product_name, quantity,
    unit_price, product_discount, voucher_discount, line_total
)
SELECT d.id, s.id, i.id, i.product_id, i.product_name, i.quantity,
       i.unit_price, i.discount_amount, i.voucher_discount_allocated, i.line_total
FROM accounting_documents d
JOIN order_items i ON i.order_id=d.order_id
LEFT JOIN accounting_document_sellers s ON s.document_id=d.id AND s.vendor_id IS NOT DISTINCT FROM i.vendor_id;

CREATE TRIGGER trg_accounting_documents_append_only
    BEFORE UPDATE OR DELETE ON accounting_documents FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();
CREATE TRIGGER trg_accounting_sellers_append_only
    BEFORE UPDATE OR DELETE ON accounting_document_sellers FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();
CREATE TRIGGER trg_accounting_lines_append_only
    BEFORE UPDATE OR DELETE ON accounting_document_lines FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();
