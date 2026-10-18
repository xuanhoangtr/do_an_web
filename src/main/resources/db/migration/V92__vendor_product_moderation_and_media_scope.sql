-- Vendor catalog moderation, visibility, and shop-scoped product media.

ALTER TABLE catalog_products
    ADD COLUMN seller_visible BOOLEAN NOT NULL DEFAULT true;

UPDATE catalog_products
SET seller_visible = active;

-- Vendor moderation applies only to vendor-owned products. Existing platform
-- catalog items remain approved, including rows inserted into a phase-2-shaped
-- schema where the synthetic default may still be PENDING.
UPDATE catalog_products
SET moderation_status = 'APPROVED',
    moderation_reason = NULL,
    moderated_at = COALESCE(moderated_at, CURRENT_TIMESTAMP)
WHERE shop_id = '00000000-0000-4000-8000-000000000001';

ALTER TABLE catalog_products
    DROP CONSTRAINT IF EXISTS chk_catalog_products_moderation_status;

ALTER TABLE catalog_products
    ADD CONSTRAINT chk_catalog_products_moderation_status
        CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED', 'BLOCKED'));

ALTER TABLE product_images ADD COLUMN shop_id UUID;
UPDATE product_images image
SET shop_id = product.shop_id
FROM catalog_products product
WHERE image.product_id = product.id AND image.shop_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM product_images image
        JOIN managed_media_objects media ON media.id = image.managed_media_id
        GROUP BY media.id
        HAVING COUNT(DISTINCT image.shop_id) > 1
    ) THEN
        RAISE EXCEPTION 'V92 refused: one managed product image is linked across multiple shops';
    END IF;
END $$;

ALTER TABLE managed_media_objects
    DROP CONSTRAINT IF EXISTS ck_managed_media_shop_purpose;

UPDATE managed_media_objects media
SET shop_id = image.shop_id
FROM product_images image
WHERE image.managed_media_id = media.id
  AND media.purpose = 'PRODUCT'
  AND media.shop_id IS NULL
  AND image.shop_id IS NOT NULL;

ALTER TABLE managed_media_objects
    ADD CONSTRAINT ck_managed_media_shop_purpose CHECK (
        (shop_id IS NULL AND application_id IS NULL)
        OR (shop_id IS NOT NULL AND (
            (purpose IN ('SHOP_LOGO', 'SHOP_BANNER')
                AND application_id IS NULL AND delivery_type = 'upload')
            OR (purpose = 'SHOP_APPLICATION_EVIDENCE'
                AND application_id IS NOT NULL AND delivery_type = 'authenticated')
            OR (purpose = 'PRODUCT'
                AND application_id IS NULL AND delivery_type = 'upload')
        ))
    );

ALTER TABLE product_images ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE product_images
    ADD CONSTRAINT fk_product_images_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_product_images_product_shop FOREIGN KEY (product_id, shop_id)
        REFERENCES catalog_products(id, shop_id) ON DELETE CASCADE;

ALTER TABLE product_images DROP CONSTRAINT IF EXISTS fk_product_images_managed_media;
ALTER TABLE product_images
    ADD CONSTRAINT fk_product_images_managed_media_shop FOREIGN KEY (managed_media_id, shop_id)
        REFERENCES managed_media_objects(id, shop_id) ON DELETE RESTRICT;

CREATE INDEX idx_product_images_shop_product_order
    ON product_images(shop_id, product_id, display_order, id);

CREATE TABLE product_moderation_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(1000),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    occurred_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_moderation_history_product_shop
        FOREIGN KEY (product_id, shop_id) REFERENCES catalog_products(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_moderation_history_from
        CHECK (from_status IS NULL OR from_status IN ('PENDING', 'APPROVED', 'REJECTED', 'BLOCKED')),
    CONSTRAINT ck_product_moderation_history_to
        CHECK (to_status IN ('PENDING', 'APPROVED', 'REJECTED', 'BLOCKED')),
    CONSTRAINT ck_product_moderation_history_reason
        CHECK (reason IS NULL OR length(reason) <= 1000)
);

CREATE INDEX idx_product_moderation_history_product_time
    ON product_moderation_history(product_id, occurred_at DESC, id DESC);
CREATE INDEX idx_product_moderation_history_shop_time
    ON product_moderation_history(shop_id, occurred_at DESC, id DESC);
