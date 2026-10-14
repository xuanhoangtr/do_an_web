-- Harden the post-migration contracts without deleting business data.

UPDATE users
   SET email_verified = false
 WHERE email_verified IS NULL;

UPDATE users
   SET email_verified_at = COALESCE(email_verified_at, created_at)
 WHERE email_verified = true
   AND email_verified_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_email_verification_challenges_expiry
    ON email_verification_challenges (expires_at, consumed_at);

CREATE INDEX IF NOT EXISTS idx_product_reviews_product_user_status
    ON product_reviews (product_id, user_id, status);
CREATE INDEX IF NOT EXISTS idx_product_reviews_order_status
    ON product_reviews (order_id, status);
CREATE INDEX IF NOT EXISTS idx_product_review_reports_status_created
    ON product_review_reports (status, created_at);

CREATE INDEX IF NOT EXISTS idx_orders_status_paid_at_created_at
    ON orders (status, paid_at, created_at);
CREATE INDEX IF NOT EXISTS idx_order_items_product_id_order_id
    ON order_items (product_id, order_id);

-- V19 is the final review-status migration. Reviews are published on create;
-- V19 maps older moderation vocabularies to PUBLISHED/HIDDEN.
