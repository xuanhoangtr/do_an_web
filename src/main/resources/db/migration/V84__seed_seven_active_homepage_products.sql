-- Add seven image-ready demo products when the known five active baseline
-- products are present. Inactive historical rows are intentionally retained.
DO $$
BEGIN
    IF (SELECT count(*) FROM catalog_products WHERE active = true) = 5
       AND (SELECT count(*) FROM catalog_products
            WHERE active = true
              AND slug IN ('hop-qua-go', 'thiep-cam-on', 'khung-anh-cuoi', 'nen-thom-cuoi', 'ly-champagne')) = 5 THEN
        IF (SELECT count(*) FROM categories
            WHERE is_active = true
              AND name IN ('Wedding Invitations', 'Wedding Gifts', 'Wedding Accessories')) <> 3 THEN
            RAISE EXCEPTION 'V84 refused: expected the three active UTEWedding demo categories';
        END IF;

        IF EXISTS (SELECT 1 FROM catalog_products
                   WHERE slug IN ('linen-wedding-photo-album', 'ceramic-ring-dish',
                                  'wedding-guest-book', 'dried-flower-table-bouquet',
                                  'personalized-vow-book-set', 'wedding-favor-gift-set',
                                  'minimal-table-number-set')) THEN
            RAISE EXCEPTION 'V84 refused: one or more demo product slugs already exist';
        END IF;

        INSERT INTO catalog_products (
            id, category_id, name, slug, sku, short_description, description,
            customization_guide, image_url, material, unit_price, compare_at_price,
            discount_percent, stock_quantity, min_order_quantity, weight_grams, dimensions,
            is_customizable, review_count, average_rating, is_featured, active, version,
            created_at, updated_at, moderation_status, moderated_at, moderated_by
        )
        SELECT product.id, category.id, product.name, product.slug, product.sku,
               product.short_description, product.description, NULL, NULL, product.material,
               product.unit_price, NULL, 0, 20, 1, product.weight_grams, product.dimensions,
               false, 0, 0.00, true, true, 0,
               CURRENT_TIMESTAMP - ((product.display_order - 1) * INTERVAL '1 second'),
               CURRENT_TIMESTAMP, 'APPROVED', CURRENT_TIMESTAMP, NULL
        FROM (VALUES
            ('11111111-1111-4111-8111-111111111201'::uuid, 'Wedding Gifts', 'Linen Wedding Photo Album', 'linen-wedding-photo-album', 'UTE-ALBUM-001', 'A linen-bound album for wedding photographs and keepsakes.', 'Linen-covered photo album with acid-free pages for preserving wedding memories.', 'Linen', 680000::bigint, 700::integer, '32 x 24 x 5 cm', 1),
            ('11111111-1111-4111-8111-111111111202'::uuid, 'Wedding Accessories', 'Ceramic Ring Dish', 'ceramic-ring-dish', 'UTE-DISH-001', 'A small ceramic dish for wedding rings and daily keepsakes.', 'Hand-finished ceramic ring dish for the ceremony table or bedside.', 'Ceramic', 190000::bigint, 280::integer, '12 x 12 x 3 cm', 2),
            ('11111111-1111-4111-8111-111111111203'::uuid, 'Wedding Accessories', 'Wedding Guest Book', 'wedding-guest-book', 'UTE-GUESTBOOK-001', 'A simple guest book for messages from family and friends.', 'Hard-cover guest book with clean pages for wedding-day messages.', 'Paper', 350000::bigint, 650::integer, '25 x 20 x 3 cm', 3),
            ('11111111-1111-4111-8111-111111111204'::uuid, 'Wedding Accessories', 'Dried Flower Table Bouquet', 'dried-flower-table-bouquet', 'UTE-BOUQUET-001', 'A preserved-flower arrangement for a wedding table or home.', 'A compact dried-flower bouquet arranged for long-lasting table decor.', 'Dried flowers', 420000::bigint, 900::integer, '30 x 20 x 20 cm', 4),
            ('11111111-1111-4111-8111-111111111205'::uuid, 'Wedding Gifts', 'Personalized Vow Book Set', 'personalized-vow-book-set', 'UTE-VOWBOOK-001', 'A pair of pocket vow books for the couple.', 'Two keepsake vow books with durable covers and lined pages.', 'Paper', 240000::bigint, 350::integer, '16 x 12 x 2 cm', 5),
            ('11111111-1111-4111-8111-111111111206'::uuid, 'Wedding Gifts', 'Wedding Favor Gift Set', 'wedding-favor-gift-set', 'UTE-FAVOR-001', 'A coordinated keepsake gift set for wedding guests.', 'A sample favor set prepared as a keepsake for wedding guests.', 'Paper and wood', 360000::bigint, 700::integer, '24 x 18 x 8 cm', 6),
            ('11111111-1111-4111-8111-111111111207'::uuid, 'Wedding Invitations', 'Minimal Table Number Set', 'minimal-table-number-set', 'UTE-TABLE-NUMBER-001', 'Minimal table numbers for a wedding reception.', 'A clean table-number set for organizing wedding reception seating.', 'Art paper', 180000::bigint, 300::integer, '20 x 15 x 2 cm', 7)
        ) AS product(id, category_name, name, slug, sku, short_description, description,
                     material, unit_price, weight_grams, dimensions, display_order)
        JOIN categories category
          ON category.name = product.category_name
         AND category.is_active = true;
    END IF;
END $$;
