-- Make catalog_products the canonical Java marketplace table. The older
-- products table is a migration source only and remains read-free at runtime.
ALTER TABLE products ADD COLUMN IF NOT EXISTS image_url VARCHAR(2048);

CREATE TABLE IF NOT EXISTS catalog_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id BIGINT REFERENCES categories(id),
    vendor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(80),
    sku VARCHAR(80),
    short_description VARCHAR(500),
    description VARCHAR(4000),
    customization_guide VARCHAR(2000),
    image_url VARCHAR(2048),
    material VARCHAR(160),
    unit_price BIGINT NOT NULL,
    compare_at_price NUMERIC(19,2),
    discount_percent INTEGER NOT NULL DEFAULT 0,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    min_order_quantity INTEGER NOT NULL DEFAULT 1,
    weight_grams INTEGER,
    dimensions VARCHAR(160),
    is_customizable BOOLEAN NOT NULL DEFAULT false,
    review_count INTEGER NOT NULL DEFAULT 0,
    average_rating NUMERIC(3,2) DEFAULT 5.00,
    is_featured BOOLEAN NOT NULL DEFAULT false,
    active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS slug VARCHAR(80);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS sku VARCHAR(80);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS short_description VARCHAR(500);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS customization_guide VARCHAR(2000);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS compare_at_price NUMERIC(19,2);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS min_order_quantity INTEGER NOT NULL DEFAULT 1;
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS weight_grams INTEGER;
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS dimensions VARCHAR(160);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS is_customizable BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS image_url VARCHAR(2048);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE catalog_products
SET slug = 'catalog-' || replace(id::text, '-', '')
WHERE slug IS NULL OR btrim(slug) = '';
UPDATE catalog_products
SET sku = 'CATALOG-' || upper(substr(replace(id::text, '-', ''), 1, 24))
WHERE sku IS NULL OR btrim(sku) = '';

-- Backfill rows that existed in the old products table. Existing canonical
-- rows win so rerunning against a partially migrated database is safe.
INSERT INTO catalog_products (
    id, category_id, vendor_id, name, slug, sku, short_description,
    description, image_url, material, unit_price, compare_at_price,
    discount_percent, stock_quantity, min_order_quantity, weight_grams,
    dimensions, is_customizable, review_count, average_rating,
    is_featured, active, version, created_at, updated_at
)
SELECT p.id, p.category_id, p.vendor_id, left(p.name, 120),
       'legacy-' || replace(p.id::text, '-', ''),
       'LEGACY-' || upper(substr(replace(p.id::text, '-', ''), 1, 24)),
       p.short_description, p.description, p.image_url, p.material,
       round(p.unit_price)::bigint, p.compare_at_price, p.discount_percent,
       p.stock_quantity, p.min_order_quantity, p.weight_grams, p.dimensions,
       p.is_customizable, p.review_count, p.average_rating, p.is_featured,
       p.is_active, 0, p.created_at, p.updated_at
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM catalog_products cp WHERE cp.id = p.id);

UPDATE catalog_products cp
SET category_id = p.category_id
FROM products p
WHERE cp.id = p.id AND cp.category_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_products WHERE category_id IS NULL) THEN
        RAISE EXCEPTION 'V3 refused: catalog_products contains rows without category_id';
    END IF;
    IF EXISTS (SELECT slug FROM catalog_products GROUP BY slug HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'V3 refused: catalog_products contains duplicate slug values';
    END IF;
    IF EXISTS (SELECT sku FROM catalog_products GROUP BY sku HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'V3 refused: catalog_products contains duplicate sku values';
    END IF;
END $$;

ALTER TABLE catalog_products ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE catalog_products ALTER COLUMN slug SET NOT NULL;
ALTER TABLE catalog_products ALTER COLUMN sku SET NOT NULL;

-- Point every product-bearing table at the canonical aggregate, regardless of
-- which foreign-key name/table a partially upgraded deployment used.
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname, c.conrelid::regclass AS table_name
        FROM pg_constraint c
        WHERE c.contype = 'f'
          AND c.conrelid IN (to_regclass('public.product_images'),
                             to_regclass('public.cart_items'),
                             to_regclass('public.order_details'),
                             to_regclass('public.order_items'))
          AND c.confrelid IN (to_regclass('public.products'),
                              to_regclass('public.catalog_products'))
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.table_name, r.conname);
    END LOOP;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_images_catalog_product') THEN
        ALTER TABLE product_images ADD CONSTRAINT fk_product_images_catalog_product
            FOREIGN KEY (product_id) REFERENCES catalog_products(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cart_items_catalog_product') THEN
        ALTER TABLE cart_items ADD CONSTRAINT fk_cart_items_catalog_product
            FOREIGN KEY (product_id) REFERENCES catalog_products(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_order_details_catalog_product') THEN
        ALTER TABLE order_details ADD CONSTRAINT fk_order_details_catalog_product
            FOREIGN KEY (product_id) REFERENCES catalog_products(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_order_items_catalog_product') THEN
        ALTER TABLE order_items ADD CONSTRAINT fk_order_items_catalog_product
            FOREIGN KEY (product_id) REFERENCES catalog_products(id);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_catalog_products_slug ON catalog_products (slug);
CREATE UNIQUE INDEX IF NOT EXISTS uk_catalog_products_sku ON catalog_products (sku);
CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id ON cart_items (cart_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cart_items_cart_product ON cart_items (cart_id, product_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (order_id);
COMMENT ON TABLE products IS 'Legacy migration source. Java runtime uses catalog_products only.';
