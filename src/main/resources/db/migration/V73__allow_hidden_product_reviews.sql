DO $$
DECLARE constraint_name TEXT;
BEGIN
    IF to_regclass('public.product_reviews') IS NULL THEN
        RETURN;
    END IF;

    FOR constraint_name IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'product_reviews'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE product_reviews DROP CONSTRAINT IF EXISTS %I', constraint_name);
    END LOOP;

    ALTER TABLE product_reviews
        ADD CONSTRAINT product_reviews_status_check_moderation
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN'));
END $$;
