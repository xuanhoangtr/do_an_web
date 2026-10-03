-- New public wishes must enter moderation instead of appearing immediately.
ALTER TABLE wishes
    ALTER COLUMN status SET DEFAULT 'PENDING'::"WishStatus";
