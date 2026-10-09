CREATE TABLE IF NOT EXISTS product_review_media (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES product_reviews(id) ON DELETE CASCADE,
    public_id VARCHAR(512),
    secure_url VARCHAR(2048) NOT NULL,
    mime_type VARCHAR(80) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_product_review_media_review_created
    ON product_review_media(review_id, created_at);
