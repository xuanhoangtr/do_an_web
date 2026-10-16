-- Shop-owned pickup/return addresses are append-only revisions. The small head
-- table points to the currently selected revision without rewriting history.
CREATE TABLE shop_address_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    address_kind VARCHAR(12) NOT NULL CHECK (address_kind IN ('PICKUP', 'RETURN')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    catalog_source VARCHAR(32) NOT NULL CHECK (catalog_source IN ('LOCAL', 'GHN_STAGING')),
    province_code VARCHAR(20) NOT NULL,
    province_name VARCHAR(255) NOT NULL CHECK (length(trim(province_name)) > 0),
    ward_code VARCHAR(20) NOT NULL,
    ward_name VARCHAR(255) NOT NULL CHECK (length(trim(ward_name)) > 0),
    address_line VARCHAR(500) NOT NULL CHECK (length(trim(address_line)) BETWEEN 1 AND 500),
    contact_name VARCHAR(160) NOT NULL CHECK (length(trim(contact_name)) BETWEEN 1 AND 160),
    contact_phone VARCHAR(30) NOT NULL CHECK (length(trim(contact_phone)) BETWEEN 7 AND 30),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_address_revision UNIQUE (shop_id, address_kind, revision),
    CONSTRAINT uq_shop_address_revision_ref UNIQUE (id, shop_id, address_kind)
);

CREATE TABLE shop_address_heads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    address_kind VARCHAR(12) NOT NULL CHECK (address_kind IN ('PICKUP', 'RETURN')),
    revision_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_address_head_kind UNIQUE (shop_id, address_kind),
    CONSTRAINT fk_shop_address_head_revision FOREIGN KEY (revision_id, shop_id, address_kind)
        REFERENCES shop_address_revisions(id, shop_id, address_kind) ON DELETE RESTRICT
);

CREATE INDEX idx_shop_address_revisions_shop_time
    ON shop_address_revisions(shop_id, address_kind, created_at DESC, revision DESC);

CREATE OR REPLACE FUNCTION prevent_shop_address_revision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Shop address revisions are immutable';
END;
$$;

CREATE TRIGGER trg_shop_address_revisions_append_only
    BEFORE UPDATE OR DELETE ON shop_address_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_shop_address_revision_mutation();

-- Only a reference to an out-of-band credential store is accepted. The actual
-- GHN token is intentionally absent from this schema and these application forms.
CREATE TABLE shipping_provider_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code VARCHAR(24) NOT NULL CHECK (provider_code = 'GHN'),
    environment VARCHAR(16) NOT NULL CHECK (environment IN ('STAGING', 'PRODUCTION')),
    account_name VARCHAR(120) NOT NULL CHECK (length(trim(account_name)) BETWEEN 1 AND 120),
    credential_reference VARCHAR(200) NOT NULL
        CHECK (credential_reference ~ '^credref:[A-Za-z0-9][A-Za-z0-9._/-]{2,180}$'
               AND credential_reference !~ '\.\.'),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'VERIFIED', 'DISABLED')),
    verification_evidence_redacted VARCHAR(500),
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ(6),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shipping_provider_account_ref UNIQUE (provider_code, environment, credential_reference),
    CONSTRAINT ck_shipping_provider_account_verified CHECK (
        (status = 'VERIFIED' AND verified_at IS NOT NULL)
        OR (status <> 'VERIFIED' AND verified_at IS NULL)
    )
);

CREATE TABLE shop_shipping_bindings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL CHECK (revision > 0),
    provider_account_id UUID NOT NULL REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT,
    external_shop_id VARCHAR(24) NOT NULL CHECK (external_shop_id ~ '^[0-9]{1,24}$'),
    pickup_address_revision_id UUID NOT NULL,
    pickup_address_kind VARCHAR(12) NOT NULL DEFAULT 'PICKUP' CHECK (pickup_address_kind = 'PICKUP'),
    return_address_revision_id UUID NOT NULL,
    return_address_kind VARCHAR(12) NOT NULL DEFAULT 'RETURN' CHECK (return_address_kind = 'RETURN'),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'DISABLED')),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    route_coverage_verified BOOLEAN NOT NULL DEFAULT FALSE,
    cod_beneficiary_verified BOOLEAN NOT NULL DEFAULT FALSE,
    cod_beneficiary_evidence_redacted VARCHAR(500),
    cod_beneficiary_verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    cod_beneficiary_verified_at TIMESTAMPTZ(6),
    evidence_redacted VARCHAR(500),
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ(6),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shop_shipping_binding_revision UNIQUE (shop_id, revision),
    CONSTRAINT uq_shop_shipping_binding_ref UNIQUE (id, shop_id),
    CONSTRAINT fk_shop_shipping_binding_pickup FOREIGN KEY
        (pickup_address_revision_id, shop_id, pickup_address_kind)
        REFERENCES shop_address_revisions(id, shop_id, address_kind) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_shipping_binding_return FOREIGN KEY
        (return_address_revision_id, shop_id, return_address_kind)
        REFERENCES shop_address_revisions(id, shop_id, address_kind) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_shipping_binding_active CHECK (
        NOT active OR (status = 'VERIFIED' AND route_coverage_verified)
    ),
    CONSTRAINT ck_shop_shipping_binding_verified CHECK (
        (status = 'VERIFIED' AND verified_at IS NOT NULL AND route_coverage_verified)
        OR (status <> 'VERIFIED' AND verified_at IS NULL AND NOT active)
    )
);

CREATE INDEX idx_shop_shipping_bindings_current
    ON shop_shipping_bindings(shop_id, active, created_at DESC);

CREATE TABLE shop_shipping_routes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    binding_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    catalog_source VARCHAR(32) NOT NULL CHECK (catalog_source IN ('LOCAL', 'GHN_STAGING')),
    province_code VARCHAR(20) NOT NULL,
    province_name VARCHAR(255) NOT NULL CHECK (length(trim(province_name)) > 0),
    ward_code VARCHAR(20) NOT NULL,
    ward_name VARCHAR(255) NOT NULL CHECK (length(trim(ward_name)) > 0),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'DISABLED')),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_redacted VARCHAR(500),
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ(6),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_shipping_routes_binding FOREIGN KEY (binding_id, shop_id)
        REFERENCES shop_shipping_bindings(id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT uq_shop_shipping_route_destination UNIQUE (binding_id, catalog_source, province_code, ward_code),
    CONSTRAINT ck_shop_shipping_routes_verified CHECK (
        (status = 'VERIFIED' AND verified_at IS NOT NULL)
        OR (status <> 'VERIFIED' AND verified_at IS NULL AND NOT active)
    ),
    CONSTRAINT ck_shop_shipping_routes_active CHECK (NOT active OR status = 'VERIFIED')
);

CREATE INDEX idx_shop_shipping_routes_binding_status
    ON shop_shipping_routes(binding_id, status, province_code, ward_code);

