-- Inventory adjustments are separate from product-content edits and preserve
-- the existing optimistic-version stock reservation protocol.
CREATE TABLE shop_inventory_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    product_id UUID NOT NULL,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    delta_quantity INTEGER NOT NULL CHECK (delta_quantity <> 0),
    quantity_before INTEGER NOT NULL CHECK (quantity_before >= 0),
    quantity_after INTEGER NOT NULL CHECK (quantity_after >= 0),
    version_before BIGINT NOT NULL CHECK (version_before >= 0),
    version_after BIGINT NOT NULL CHECK (version_after > version_before),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_inventory_adjustments_product_shop
        FOREIGN KEY (product_id, shop_id) REFERENCES catalog_products(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_inventory_adjustments_delta
        CHECK (quantity_after - quantity_before = delta_quantity)
);

CREATE INDEX idx_shop_inventory_adjustments_shop_time
    ON shop_inventory_adjustments(shop_id, created_at DESC, id DESC);
CREATE INDEX idx_shop_inventory_adjustments_product_time
    ON shop_inventory_adjustments(product_id, created_at DESC, id DESC);

-- An active catalog product must satisfy the same eligibility conditions used
-- by public catalog, cart, quote and checkout reads. Shop state is materialized
-- into active here so existing active-only queries remain compatible.
UPDATE catalog_products p
SET active = false
FROM shops s
WHERE s.id = p.shop_id
  AND p.active = true
  AND (p.seller_visible = false OR p.moderation_status <> 'APPROVED' OR s.status <> 'ACTIVE');

ALTER TABLE catalog_products
    DROP CONSTRAINT IF EXISTS ck_catalog_products_active_sellable;
ALTER TABLE catalog_products
    ADD CONSTRAINT ck_catalog_products_active_sellable
        CHECK (NOT active OR (seller_visible AND moderation_status = 'APPROVED'));

CREATE OR REPLACE FUNCTION enforce_catalog_product_buyability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_shop_kind VARCHAR(20);
    resolved_shop_status VARCHAR(20);
BEGIN
    SELECT kind, status INTO resolved_shop_kind, resolved_shop_status
    FROM shops
    WHERE id = NEW.shop_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'catalog product requires an existing shop'
            USING ERRCODE = '23503';
    END IF;

    NEW.active := NEW.active
        AND NEW.seller_visible
        AND NEW.moderation_status = 'APPROVED'
        AND resolved_shop_status = 'ACTIVE';

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_catalog_product_buyability ON catalog_products;
CREATE TRIGGER trg_catalog_product_buyability
    BEFORE INSERT OR UPDATE OF active, seller_visible, moderation_status, shop_id
    ON catalog_products
    FOR EACH ROW
    EXECUTE FUNCTION enforce_catalog_product_buyability();

CREATE OR REPLACE FUNCTION sync_catalog_products_after_shop_status_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        UPDATE catalog_products
        SET active = (NEW.status = 'ACTIVE' AND seller_visible AND moderation_status = 'APPROVED')
        WHERE shop_id = NEW.id
          AND active IS DISTINCT FROM (NEW.status = 'ACTIVE' AND seller_visible
                                       AND moderation_status = 'APPROVED');
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_catalog_products_after_shop_status_change ON shops;
CREATE TRIGGER trg_sync_catalog_products_after_shop_status_change
    AFTER UPDATE OF status ON shops
    FOR EACH ROW
    EXECUTE FUNCTION sync_catalog_products_after_shop_status_change();
