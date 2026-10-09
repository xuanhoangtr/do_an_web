-- Link generated AI product and gallery images to catalog products

-- 1. Update primary image_url and clean human-readable slugs on catalog_products table
UPDATE catalog_products
SET slug = 'thiep-cuoi-premium'
WHERE id = '11111111-1111-4111-8111-111111111101'::uuid
  AND NOT EXISTS (SELECT 1 FROM catalog_products WHERE slug = 'thiep-cuoi-premium' AND id <> '11111111-1111-4111-8111-111111111101'::uuid);

UPDATE catalog_products
SET image_url = '/images/products/wooden-gift-box.webp',
    slug = CASE WHEN NOT EXISTS (SELECT 1 FROM catalog_products WHERE slug = 'hop-qua-go' AND id <> '11111111-1111-4111-8111-111111111102'::uuid) THEN 'hop-qua-go' ELSE slug END
WHERE id = '11111111-1111-4111-8111-111111111102'::uuid;

UPDATE catalog_products
SET image_url = '/images/products/champagne-glass-set.webp',
    slug = CASE WHEN NOT EXISTS (SELECT 1 FROM catalog_products WHERE slug = 'ly-champagne' AND id <> '11111111-1111-4111-8111-111111111103'::uuid) THEN 'ly-champagne' ELSE slug END
WHERE id = '11111111-1111-4111-8111-111111111103'::uuid;

UPDATE catalog_products
SET image_url = '/images/products/scented-candle.webp',
    slug = CASE WHEN NOT EXISTS (SELECT 1 FROM catalog_products WHERE slug = 'nen-thom-cuoi' AND id <> '11111111-1111-4111-8111-111111111104'::uuid) THEN 'nen-thom-cuoi' ELSE slug END
WHERE id = '11111111-1111-4111-8111-111111111104'::uuid;

UPDATE catalog_products
SET image_url = '/images/products/photo-frame.webp',
    slug = CASE WHEN NOT EXISTS (SELECT 1 FROM catalog_products WHERE slug = 'khung-anh-cuoi' AND id <> '11111111-1111-4111-8111-111111111105'::uuid) THEN 'khung-anh-cuoi' ELSE slug END
WHERE id = '11111111-1111-4111-8111-111111111105'::uuid;

UPDATE catalog_products
SET image_url = '/images/products/thank-you-cards.webp',
    slug = CASE WHEN NOT EXISTS (SELECT 1 FROM catalog_products WHERE slug = 'thiep-cam-on' AND id <> '11111111-1111-4111-8111-111111111106'::uuid) THEN 'thiep-cam-on' ELSE slug END
WHERE id = '11111111-1111-4111-8111-111111111106'::uuid;

-- 2. Populate product_images gallery table
INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111102'::uuid, '/images/products/wooden-gift-box.webp', 'Engraved Wooden Wedding Gift Box - Studio Shot', 'IMAGE', 0, true
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111102'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111102'::uuid AND display_order = 0);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111102'::uuid, '/images/products/wooden-gift-box-detail.webp', 'Engraved Wooden Wedding Gift Box - Reception Lifestyle Shot', 'IMAGE', 1, false
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111102'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111102'::uuid AND display_order = 1);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111103'::uuid, '/images/products/champagne-glass-set.webp', 'Couple Champagne Glass Set - Studio Shot', 'IMAGE', 0, true
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111103'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111103'::uuid AND display_order = 0);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111103'::uuid, '/images/products/champagne-glass-set-detail.webp', 'Couple Champagne Glass Set - Banquet Lifestyle Shot', 'IMAGE', 1, false
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111103'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111103'::uuid AND display_order = 1);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111104'::uuid, '/images/products/scented-candle.webp', 'Wedding Scented Candle - Studio Shot', 'IMAGE', 0, true
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111104'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111104'::uuid AND display_order = 0);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111104'::uuid, '/images/products/scented-candle-detail.webp', 'Wedding Scented Candle - Ambient Flame Shot', 'IMAGE', 1, false
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111104'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111104'::uuid AND display_order = 1);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111105'::uuid, '/images/products/photo-frame.webp', 'Minimal Wedding Photo Frame - Studio Shot', 'IMAGE', 0, true
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111105'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111105'::uuid AND display_order = 0);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111105'::uuid, '/images/products/photo-frame-detail.webp', 'Minimal Wedding Photo Frame - Table Decor Shot', 'IMAGE', 1, false
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111105'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111105'::uuid AND display_order = 1);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111106'::uuid, '/images/products/thank-you-cards.webp', 'Thank You Cards for Guests - Flatlay Shot', 'IMAGE', 0, true
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111106'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111106'::uuid AND display_order = 0);

INSERT INTO product_images (product_id, image_url, alt_text, media_type, display_order, is_primary)
SELECT '11111111-1111-4111-8111-111111111106'::uuid, '/images/products/thank-you-cards-detail.webp', 'Thank You Cards for Guests - Gift Favor Shot', 'IMAGE', 1, false
WHERE EXISTS (SELECT 1 FROM catalog_products WHERE id = '11111111-1111-4111-8111-111111111106'::uuid)
  AND NOT EXISTS (SELECT 1 FROM product_images WHERE product_id = '11111111-1111-4111-8111-111111111106'::uuid AND display_order = 1);
