-- Finalize the review moderation contract. Earlier migrations briefly used
-- PUBLISHED/HIDDEN while the MVP was being assembled; the runtime contract is
-- now PENDING -> APPROVED/REJECTED.
DO $$
DECLARE c TEXT;
BEGIN
    IF to_regclass('public.product_reviews') IS NULL THEN
        RETURN;
    END IF;

    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'product_reviews'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE product_reviews DROP CONSTRAINT IF EXISTS %I', c);
    END LOOP;

    UPDATE product_reviews
       SET status = CASE
           WHEN status IN ('APPROVED', 'PUBLISHED') THEN 'APPROVED'
           WHEN status IN ('REJECTED', 'HIDDEN') THEN 'REJECTED'
           ELSE 'PENDING'
       END;

    ALTER TABLE product_reviews
        ADD CONSTRAINT product_reviews_status_check_moderation
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
END $$;

CREATE INDEX IF NOT EXISTS idx_product_reviews_status_created
    ON product_reviews(status, created_at);
