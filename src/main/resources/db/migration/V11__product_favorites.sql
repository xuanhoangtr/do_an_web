CREATE TABLE IF NOT EXISTS product_favorites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES catalog_products(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_favorites_user_product UNIQUE (user_id, product_id)
);
CREATE INDEX IF NOT EXISTS idx_product_favorites_product ON product_favorites(product_id);
