CREATE TABLE product_view_history (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES catalog_products(id) ON DELETE CASCADE,
    last_viewed_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_product_view_history PRIMARY KEY (user_id, product_id)
);

CREATE INDEX idx_product_view_history_user_recent
    ON product_view_history (user_id, last_viewed_at DESC, product_id DESC);

CREATE INDEX idx_product_view_history_expiry
    ON product_view_history (last_viewed_at);
