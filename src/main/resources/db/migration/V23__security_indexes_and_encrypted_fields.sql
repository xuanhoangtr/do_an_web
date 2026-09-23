-- Security/performance hardening. Keep V1 and prior migrations immutable.

-- AES-GCM envelopes are longer than their plaintext values. Existing plaintext
-- remains readable by the converter; an application backfill encrypts it in a
-- controlled batch after this schema widening migration.
ALTER TABLE ship_infos
    ALTER COLUMN recipient_name TYPE VARCHAR(4096),
    ALTER COLUMN phone TYPE VARCHAR(4096),
    ALTER COLUMN address_line TYPE VARCHAR(4096),
    ALTER COLUMN ward TYPE VARCHAR(4096),
    ALTER COLUMN district TYPE VARCHAR(4096),
    ALTER COLUMN province TYPE VARCHAR(4096),
    ALTER COLUMN postal_code TYPE VARCHAR(4096),
    ALTER COLUMN note TYPE VARCHAR(4096);

ALTER TABLE orders
    ALTER COLUMN shipping_recipient_name TYPE VARCHAR(4096),
    ALTER COLUMN shipping_phone TYPE VARCHAR(4096),
    ALTER COLUMN shipping_address TYPE VARCHAR(4096),
    ALTER COLUMN shipping_ward TYPE VARCHAR(4096),
    ALTER COLUMN shipping_district TYPE VARCHAR(4096),
    ALTER COLUMN shipping_province TYPE VARCHAR(4096);

ALTER TABLE guests
    ALTER COLUMN phone TYPE VARCHAR(4096),
    ALTER COLUMN email TYPE VARCHAR(4096),
    ALTER COLUMN invitation_message_override TYPE VARCHAR(4096);

ALTER TABLE rsvps
    ALTER COLUMN guest_name TYPE VARCHAR(4096),
    ALTER COLUMN phone TYPE VARCHAR(4096),
    ALTER COLUMN note TYPE VARCHAR(4096),
    ALTER COLUMN dietary_notes TYPE VARCHAR(4096);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_revoked
    ON auth_sessions(user_id, revoked_at);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_family_id
    ON auth_sessions(family_id);
CREATE INDEX IF NOT EXISTS idx_media_assets_wedding_status_display
    ON media_assets(wedding_id, status, display_order);
CREATE INDEX IF NOT EXISTS idx_payments_order_id
    ON payments(order_id);
CREATE INDEX IF NOT EXISTS idx_wedding_ceremonies_wedding_display
    ON wedding_ceremonies(wedding_id, display_order);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id
    ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id
    ON cart_items(cart_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cart_items_cart_product
    ON cart_items(cart_id, product_id);
