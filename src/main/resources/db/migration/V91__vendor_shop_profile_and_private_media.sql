-- Vendor profile fields and shop-scoped media ownership.
ALTER TABLE shops
    ADD COLUMN description VARCHAR(2000),
    ADD COLUMN contact_email VARCHAR(320),
    ADD COLUMN contact_phone VARCHAR(30),
    ADD COLUMN logo_media_id UUID,
    ADD COLUMN banner_media_id UUID;

ALTER TABLE managed_media_objects
    ADD COLUMN shop_id UUID,
    ADD COLUMN application_id UUID;

ALTER TABLE managed_media_objects
    ADD CONSTRAINT fk_managed_media_shop
        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_managed_media_shop_application
        FOREIGN KEY (application_id, shop_id) REFERENCES shop_applications(id, shop_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_managed_media_id_shop UNIQUE (id, shop_id),
    ADD CONSTRAINT ck_managed_media_shop_purpose CHECK (
        (shop_id IS NULL AND application_id IS NULL)
        OR (shop_id IS NOT NULL AND (
            (purpose IN ('SHOP_LOGO', 'SHOP_BANNER')
                AND application_id IS NULL AND delivery_type = 'upload')
            OR (purpose = 'SHOP_APPLICATION_EVIDENCE'
                AND application_id IS NOT NULL AND delivery_type = 'authenticated')
        ))
    );

ALTER TABLE shops
    ADD CONSTRAINT fk_shops_logo_media_shop
        FOREIGN KEY (logo_media_id, id) REFERENCES managed_media_objects(id, shop_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_shops_banner_media_shop
        FOREIGN KEY (banner_media_id, id) REFERENCES managed_media_objects(id, shop_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_shops_profile_contact_email
        CHECK (contact_email IS NULL OR length(contact_email) <= 320),
    ADD CONSTRAINT ck_shops_profile_contact_phone
        CHECK (contact_phone IS NULL OR length(contact_phone) <= 30);

CREATE INDEX idx_managed_media_shop_purpose_status_created
    ON managed_media_objects(shop_id, purpose, status, created_at DESC)
    WHERE shop_id IS NOT NULL;

CREATE INDEX idx_managed_media_application_purpose_status
    ON managed_media_objects(application_id, purpose, status)
    WHERE application_id IS NOT NULL;
