ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'VENDOR_APPLICATION_SUBMITTED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'VENDOR_STATUS_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PRODUCT_MODERATION_CHANGED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PRIVACY_EXPORT_COMPLETED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PRIVACY_DELETION_REQUESTED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PRIVACY_DELETION_COMPLETED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PRIVACY_REQUEST_REJECTED';
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'PRIVACY_CONSENT_RECORDED';

CREATE TABLE IF NOT EXISTS vendor_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    business_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    shop_name VARCHAR(160) NOT NULL,
    legal_name VARCHAR(4096),
    tax_code VARCHAR(4096),
    registration_number VARCHAR(4096),
    contact_email VARCHAR(4096),
    contact_phone VARCHAR(4096),
    business_address VARCHAR(4096),
    description VARCHAR(2000),
    decision_reason VARCHAR(4096),
    submitted_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMPTZ(6),
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_vendor_profiles_status
        CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
    CONSTRAINT chk_vendor_profiles_business_type
        CHECK (business_type IN ('INDIVIDUAL','HOUSEHOLD','COMPANY'))
);

CREATE INDEX IF NOT EXISTS idx_vendor_profiles_status_submitted
    ON vendor_profiles(status, submitted_at, id);
CREATE INDEX IF NOT EXISTS idx_vendor_profiles_reviewed_by
    ON vendor_profiles(reviewed_by) WHERE reviewed_by IS NOT NULL;

CREATE TABLE IF NOT EXISTS vendor_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_profile_id UUID NOT NULL REFERENCES vendor_profiles(id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(4096),
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_vendor_status_history_from
        CHECK (from_status IS NULL OR from_status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
    CONSTRAINT chk_vendor_status_history_to
        CHECK (to_status IN ('PENDING','APPROVED','REJECTED','SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_vendor_status_history_profile_created
    ON vendor_status_history(vendor_profile_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_vendor_status_history_actor
    ON vendor_status_history(actor_id) WHERE actor_id IS NOT NULL;

INSERT INTO vendor_profiles (
    user_id, status, business_type, shop_name, legal_name, contact_email,
    submitted_at, reviewed_at, created_at, updated_at
)
SELECT u.id, 'APPROVED', 'INDIVIDUAL', left(u.display_name, 160), u.display_name, u.email,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP
FROM users u
WHERE u.role = 'VENDOR'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO vendor_profiles (
    user_id, status, business_type, shop_name, legal_name, contact_email,
    submitted_at, reviewed_at, created_at, updated_at
)
SELECT DISTINCT u.id, 'APPROVED', 'INDIVIDUAL', left(u.display_name, 160), u.display_name, u.email,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP
FROM catalog_products p
JOIN users u ON u.id = p.vendor_id
WHERE p.vendor_id IS NOT NULL
ON CONFLICT (user_id) DO NOTHING;

ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(20);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS moderation_reason VARCHAR(4096);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS moderated_at TIMESTAMPTZ(6);
ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS moderated_by UUID;

UPDATE catalog_products
SET moderation_status = 'APPROVED',
    moderated_at = COALESCE(moderated_at, CURRENT_TIMESTAMP)
WHERE moderation_status IS NULL;

ALTER TABLE catalog_products ALTER COLUMN moderation_status SET DEFAULT 'PENDING';
ALTER TABLE catalog_products ALTER COLUMN moderation_status SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_catalog_products_moderation_status'
          AND conrelid = 'catalog_products'::regclass
    ) THEN
        ALTER TABLE catalog_products
            ADD CONSTRAINT chk_catalog_products_moderation_status
            CHECK (moderation_status IN ('PENDING','APPROVED','REJECTED'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_catalog_products_moderated_by'
          AND conrelid = 'catalog_products'::regclass
    ) THEN
        ALTER TABLE catalog_products
            ADD CONSTRAINT fk_catalog_products_moderated_by
            FOREIGN KEY (moderated_by) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_catalog_products_moderation_created
    ON catalog_products(moderation_status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_catalog_products_vendor_moderation_active
    ON catalog_products(vendor_id, moderation_status, active);
CREATE INDEX IF NOT EXISTS idx_catalog_products_moderated_by
    ON catalog_products(moderated_by) WHERE moderated_by IS NOT NULL;

CREATE OR REPLACE FUNCTION enforce_approved_vendor_product()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.vendor_id IS NOT NULL AND NEW.active THEN
        IF NEW.moderation_status <> 'APPROVED' THEN
            RAISE EXCEPTION 'active vendor product requires approved vendor and product moderation'
                USING ERRCODE = '23514';
        END IF;

        -- Serialize product activation with vendor suspension. Without a row lock,
        -- an activation statement that started before suspension committed could
        -- validate against an obsolete snapshot and leave an active product behind.
        PERFORM 1
        FROM vendor_profiles vp
        WHERE vp.user_id = NEW.vendor_id AND vp.status = 'APPROVED'
        FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'active vendor product requires approved vendor and product moderation'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_catalog_products_approved_vendor ON catalog_products;
CREATE TRIGGER trg_catalog_products_approved_vendor
BEFORE INSERT OR UPDATE OF vendor_id, active, moderation_status
ON catalog_products
FOR EACH ROW EXECUTE FUNCTION enforce_approved_vendor_product();

CREATE OR REPLACE FUNCTION deactivate_products_for_unapproved_vendor()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'APPROVED' AND (OLD.status IS DISTINCT FROM NEW.status) THEN
        UPDATE catalog_products
        SET active = false, updated_at = CURRENT_TIMESTAMP
        WHERE vendor_id = NEW.user_id AND active = true;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_vendor_status_deactivate_products ON vendor_profiles;
CREATE TRIGGER trg_vendor_status_deactivate_products
AFTER UPDATE OF status
ON vendor_profiles
FOR EACH ROW EXECUTE FUNCTION deactivate_products_for_unapproved_vendor();

CREATE TABLE IF NOT EXISTS privacy_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    request_type VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    request_reason VARCHAR(4096),
    decision_reason VARCHAR(4096),
    processed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    requested_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_privacy_requests_type CHECK (request_type IN ('EXPORT','DELETION')),
    CONSTRAINT chk_privacy_requests_status
        CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','PARTIALLY_COMPLETED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_privacy_requests_user_created
    ON privacy_requests(user_id, requested_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_privacy_requests_status_created
    ON privacy_requests(status, requested_at, id);
CREATE INDEX IF NOT EXISTS idx_privacy_requests_processed_by
    ON privacy_requests(processed_by) WHERE processed_by IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_privacy_open_request
    ON privacy_requests(user_id, request_type)
    WHERE status IN ('PENDING','IN_PROGRESS');

CREATE TABLE IF NOT EXISTS privacy_consent_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    purpose VARCHAR(80) NOT NULL,
    granted BOOLEAN NOT NULL,
    legal_basis VARCHAR(30) NOT NULL,
    notice_version VARCHAR(40) NOT NULL,
    source VARCHAR(40) NOT NULL,
    recorded_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_privacy_consent_legal_basis
        CHECK (legal_basis IN ('CONSENT','CONTRACT','LEGAL_OBLIGATION','LEGITIMATE_INTEREST'))
);

CREATE INDEX IF NOT EXISTS idx_privacy_consent_user_purpose_recorded
    ON privacy_consent_records(user_id, purpose, recorded_at DESC, id DESC);

CREATE OR REPLACE FUNCTION prevent_privacy_consent_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'privacy consent history is append-only'
        USING ERRCODE = '55000';
END $$;

DROP TRIGGER IF EXISTS trg_privacy_consent_append_only ON privacy_consent_records;
CREATE TRIGGER trg_privacy_consent_append_only
BEFORE UPDATE OR DELETE
ON privacy_consent_records
FOR EACH ROW EXECUTE FUNCTION prevent_privacy_consent_mutation();

CREATE TABLE IF NOT EXISTS privacy_retention_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_key VARCHAR(80) NOT NULL UNIQUE,
    retention_days INTEGER,
    trigger_event VARCHAR(80) NOT NULL,
    disposition VARCHAR(30) NOT NULL,
    legal_basis VARCHAR(30) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_privacy_retention_days CHECK (retention_days IS NULL OR retention_days >= 0),
    CONSTRAINT chk_privacy_retention_disposition
        CHECK (disposition IN ('DELETE','ANONYMIZE','RETAIN','REVIEW')),
    CONSTRAINT chk_privacy_retention_legal_basis
        CHECK (legal_basis IN ('CONSENT','CONTRACT','LEGAL_OBLIGATION','LEGITIMATE_INTEREST'))
);

INSERT INTO privacy_retention_rules
    (category_key, retention_days, trigger_event, disposition, legal_basis, description)
VALUES
    ('ACCOUNT_PROFILE', 0, 'APPROVED_DELETION_REQUEST', 'ANONYMIZE', 'CONTRACT',
     'Pseudonymize the account after active transactions are complete.'),
    ('SAVED_ADDRESS', 0, 'APPROVED_DELETION_REQUEST', 'DELETE', 'CONTRACT',
     'Delete saved address-book entries after an approved deletion request.'),
    ('ORDER_TRANSACTION', 3650, 'ORDER_COMPLETED', 'REVIEW', 'LEGAL_OBLIGATION',
     'Keep financial facts; redact delivery PII when deletion is approved and fulfillment is complete.'),
    ('SECURITY_AUDIT', 730, 'EVENT_CREATED', 'ANONYMIZE', 'LEGITIMATE_INTEREST',
     'Keep event integrity while removing request context during account anonymization.'),
    ('CONSENT_HISTORY', 1825, 'CONSENT_RECORDED', 'RETAIN', 'LEGAL_OBLIGATION',
     'Retain consent evidence for the configured period.'),
    ('PRIVACY_REQUEST', 1825, 'REQUEST_COMPLETED', 'RETAIN', 'LEGAL_OBLIGATION',
     'Retain evidence that a privacy request was handled.')
ON CONFLICT (category_key) DO NOTHING;
