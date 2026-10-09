-- UTEWedding completion migration: canonical payment lifecycle, reviews and ranking indexes.
-- This migration must not collapse fulfillment history into PAID. Payment and
-- fulfillment are separate concerns; preserve every existing lifecycle state.

DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.orders') IS NOT NULL THEN
        FOR c IN
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'orders'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) ILIKE '%status%'
        LOOP
            EXECUTE format('ALTER TABLE orders DROP CONSTRAINT IF EXISTS %I', c);
        END LOOP;
        ALTER TABLE orders ADD CONSTRAINT orders_status_check_canonical
            CHECK (status IN ('PENDING','PAID','CONFIRMED','PROCESSING','SHIPPING',
                              'SHIPPED','DELIVERED','CANCELLED','EXPIRED','REFUNDED','RETURNED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_orders_status_expires_at
    ON orders(status, expires_at);
CREATE INDEX IF NOT EXISTS idx_orders_status_paid_at
    ON orders(status, paid_at, created_at);
CREATE INDEX IF NOT EXISTS idx_order_items_product_order
    ON order_items(product_id, order_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_family_revoked
    ON auth_sessions(family_id, revoked_at, generation);

ALTER TABLE product_reviews ADD COLUMN IF NOT EXISTS content VARCHAR(2000);
ALTER TABLE product_reviews ADD COLUMN IF NOT EXISTS order_id UUID;
UPDATE product_reviews SET content = comment
WHERE content IS NULL AND to_regclass('public.product_reviews') IS NOT NULL;
-- Do not rewrite review statuses while the V10 constraint is still active.
-- V14 performs the explicit legacy PUBLISHED/HIDDEN -> APPROVED/REJECTED
-- conversion after it has replaced that constraint.  Keeping this migration
-- schema-only makes a fresh Flyway run and an upgrade of an existing V10
-- database behave identically.
DO $$
BEGIN
    IF to_regclass('public.product_reviews') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = 'product_reviews'::regclass
                         AND conname = 'fk_product_reviews_order') THEN
        ALTER TABLE product_reviews ADD CONSTRAINT fk_product_reviews_order
            FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT;
    END IF;
END $$;
DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.product_reviews') IS NOT NULL THEN
        FOR c IN
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'product_reviews'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) ILIKE '%status%'
        LOOP
            EXECUTE format('ALTER TABLE product_reviews DROP CONSTRAINT IF EXISTS %I', c);
        END LOOP;
        UPDATE product_reviews SET status = CASE
            WHEN status IN ('APPROVED','PUBLISHED') THEN 'PUBLISHED'
            ELSE 'HIDDEN'
        END;
        ALTER TABLE product_reviews ADD CONSTRAINT product_reviews_status_check_canonical
            CHECK (status IN ('PUBLISHED','HIDDEN'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_product_reviews_order_status
    ON product_reviews(order_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_product_reviews_user_product
    ON product_reviews(product_id, user_id);

CREATE TABLE IF NOT EXISTS product_review_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES product_reviews(id) ON DELETE CASCADE,
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','RESOLVED','DISMISSED')),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_review_reports_review_reporter UNIQUE (review_id, reporter_id)
);
CREATE INDEX IF NOT EXISTS idx_review_reports_review_status
    ON product_review_reports(review_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_review_reports_reporter
    ON product_review_reports(reporter_id, created_at);

ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'REVIEW_CREATED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'REVIEW_UPDATED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'REVIEW_DELETED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'REVIEW_REPORTED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'REVIEW_HIDDEN';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'REVIEW_RESTORED';
