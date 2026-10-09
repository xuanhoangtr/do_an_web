-- Finish the review migration after the legacy V10/V12 shape has been applied.
-- The runtime contract is PENDING -> APPROVED/REJECTED and every review must
-- point to the paid order that made it eligible.
DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.product_reviews') IS NULL THEN
        RETURN;
    END IF;

    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'product_reviews'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE product_reviews DROP CONSTRAINT IF EXISTS %I', c);
    END LOOP;

    UPDATE product_reviews
       SET content = COALESCE(NULLIF(BTRIM(content), ''), NULLIF(BTRIM(comment), ''))
     WHERE content IS NULL OR BTRIM(content) = '';

    UPDATE product_reviews SET status = CASE status
        WHEN 'PUBLISHED' THEN 'APPROVED'
        WHEN 'HIDDEN' THEN 'REJECTED'
        ELSE status
    END;

    -- Older reviews did not store the qualifying order. Recover the link
    -- whenever the user/product pair has a paid order. Reviews for which no
    -- such order exists cannot satisfy the new verified-purchase contract;
    -- preserve them in a quarantine table before removing them from the
    -- runtime table instead of making the whole Flyway upgrade fail.
    UPDATE product_reviews review
       SET order_id = (
           SELECT item.order_id
             FROM order_items item
             JOIN orders order_header ON order_header.id = item.order_id
            WHERE item.product_id = review.product_id
              AND order_header.user_id = review.user_id
              AND order_header.status IN ('PAID','CONFIRMED','PROCESSING','SHIPPING',
                                          'SHIPPED','DELIVERED','RETURNED','REFUNDED')
            ORDER BY order_header.paid_at DESC NULLS LAST, order_header.created_at DESC
            LIMIT 1
       )
     WHERE order_id IS NULL;

    CREATE TABLE IF NOT EXISTS legacy_product_reviews_unlinked (
        id UUID PRIMARY KEY,
        product_id UUID NOT NULL,
        user_id UUID NOT NULL,
        rating INTEGER NOT NULL,
        comment VARCHAR(2000) NOT NULL,
        content VARCHAR(2000),
        status VARCHAR(20) NOT NULL,
        created_at TIMESTAMPTZ(6),
        updated_at TIMESTAMPTZ(6),
        archived_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
    INSERT INTO legacy_product_reviews_unlinked
        (id, product_id, user_id, rating, comment, content, status, created_at, updated_at)
    SELECT id, product_id, user_id, rating, comment, content, status, created_at, updated_at
      FROM product_reviews
     WHERE order_id IS NULL
    ON CONFLICT (id) DO NOTHING;
    DELETE FROM product_reviews WHERE order_id IS NULL;

    IF EXISTS (SELECT 1 FROM product_reviews WHERE content IS NULL OR order_id IS NULL) THEN
        RAISE EXCEPTION 'product_reviews contains rows without content/order_id; backfill them before V14';
    END IF;

    ALTER TABLE product_reviews ALTER COLUMN content SET NOT NULL;
    ALTER TABLE product_reviews ALTER COLUMN order_id SET NOT NULL;
    ALTER TABLE product_reviews ADD CONSTRAINT product_reviews_status_check_canonical
        CHECK (status IN ('PENDING','APPROVED','REJECTED'));
END $$;

ALTER TABLE product_reviews ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_product_reviews_order_status
    ON product_reviews(order_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_product_reviews_user_product
    ON product_reviews(product_id, user_id);
