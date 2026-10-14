-- Product reviews are published immediately; moderation only hides/restores.
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
           WHEN status IN ('APPROVED','PUBLISHED','PENDING') THEN 'PUBLISHED'
           ELSE 'HIDDEN'
       END;
    ALTER TABLE product_reviews ADD CONSTRAINT product_reviews_status_check_visibility
        CHECK (status IN ('PUBLISHED','HIDDEN'));
END $$;
