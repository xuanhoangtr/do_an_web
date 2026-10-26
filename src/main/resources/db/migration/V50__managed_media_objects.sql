-- Provider-owned media lifecycle. Legacy URL-only rows remain unmanaged.
CREATE TABLE IF NOT EXISTS managed_media_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(40) NOT NULL DEFAULT 'CLOUDINARY',
    resource_type VARCHAR(20) NOT NULL DEFAULT 'image',
    delivery_type VARCHAR(30) NOT NULL DEFAULT 'upload',
    public_id VARCHAR(512) NOT NULL,
    secure_url VARCHAR(2048),
    detected_mime VARCHAR(80),
    detected_format VARCHAR(20),
    byte_size BIGINT,
    width INTEGER,
    height INTEGER,
    pixel_count BIGINT,
    sha256 CHAR(64),
    purpose VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
    retain_until TIMESTAMPTZ(6),
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    legal_hold_reason VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ(6),
    last_error VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ(6),
    CONSTRAINT managed_media_provider_key UNIQUE (provider, resource_type, delivery_type, public_id),
    CONSTRAINT managed_media_status_check CHECK (status IN ('UPLOADING','READY','DELETE_PENDING','DELETING','DELETED','FAILED')),
    CONSTRAINT managed_media_size_check CHECK (byte_size IS NULL OR byte_size > 0),
    CONSTRAINT managed_media_dimensions_check CHECK ((width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)),
    CONSTRAINT managed_media_pixel_check CHECK (pixel_count IS NULL OR pixel_count > 0)
);
CREATE INDEX IF NOT EXISTS idx_managed_media_cleanup
    ON managed_media_objects(status, next_retry_at, retain_until, id)
    WHERE status IN ('DELETE_PENDING','DELETING','FAILED');
CREATE INDEX IF NOT EXISTS idx_managed_media_retention
    ON managed_media_objects(retain_until, legal_hold, status);

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS managed_media_id UUID;
ALTER TABLE product_images ADD COLUMN IF NOT EXISTS managed_media_id UUID;
ALTER TABLE product_review_media ADD COLUMN IF NOT EXISTS managed_media_id UUID;
ALTER TABLE order_fulfillments ADD COLUMN IF NOT EXISTS delivery_proof_media_id UUID;
ALTER TABLE delivery_attempts ADD COLUMN IF NOT EXISTS proof_media_id UUID;
ALTER TABLE return_evidence ADD COLUMN IF NOT EXISTS managed_media_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_media_assets_managed_media') THEN
        ALTER TABLE media_assets ADD CONSTRAINT fk_media_assets_managed_media
            FOREIGN KEY (managed_media_id) REFERENCES managed_media_objects(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_images_managed_media') THEN
        ALTER TABLE product_images ADD CONSTRAINT fk_product_images_managed_media
            FOREIGN KEY (managed_media_id) REFERENCES managed_media_objects(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_review_media_managed_media') THEN
        ALTER TABLE product_review_media ADD CONSTRAINT fk_product_review_media_managed_media
            FOREIGN KEY (managed_media_id) REFERENCES managed_media_objects(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_fulfillments_proof_media') THEN
        ALTER TABLE order_fulfillments ADD CONSTRAINT fk_fulfillments_proof_media
            FOREIGN KEY (delivery_proof_media_id) REFERENCES managed_media_objects(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_delivery_attempts_proof_media') THEN
        ALTER TABLE delivery_attempts ADD CONSTRAINT fk_delivery_attempts_proof_media
            FOREIGN KEY (proof_media_id) REFERENCES managed_media_objects(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_return_evidence_managed_media') THEN
        ALTER TABLE return_evidence ADD CONSTRAINT fk_return_evidence_managed_media
            FOREIGN KEY (managed_media_id) REFERENCES managed_media_objects(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_media_assets_managed_media ON media_assets(managed_media_id) WHERE managed_media_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_product_images_managed_media ON product_images(managed_media_id) WHERE managed_media_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_product_review_media_managed_media ON product_review_media(managed_media_id) WHERE managed_media_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fulfillments_proof_media ON order_fulfillments(delivery_proof_media_id) WHERE delivery_proof_media_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_delivery_attempts_proof_media ON delivery_attempts(proof_media_id) WHERE proof_media_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_return_evidence_managed_media ON return_evidence(managed_media_id) WHERE managed_media_id IS NOT NULL;