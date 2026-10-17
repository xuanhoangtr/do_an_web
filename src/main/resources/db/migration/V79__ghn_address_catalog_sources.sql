-- Keep the pinned local catalog intact while storing GHN's two-level Staging
-- identifiers alongside their origin and provider metadata.
ALTER TABLE provinces
    ADD COLUMN catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN catalog_type VARCHAR(24),
    ADD COLUMN provider_status INTEGER,
    ADD COLUMN catalog_synced_at TIMESTAMPTZ;

ALTER TABLE wards
    ADD COLUMN catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN catalog_type VARCHAR(24),
    ADD COLUMN provider_status INTEGER,
    ADD COLUMN catalog_synced_at TIMESTAMPTZ;

ALTER TABLE ship_infos
    ADD COLUMN address_catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE orders
    ADD COLUMN shipping_address_catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE shipping_quotes
    ADD COLUMN address_catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE order_shipping_snapshots
    ADD COLUMN address_catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE provinces ADD CONSTRAINT ck_provinces_catalog_source
    CHECK (catalog_source IN ('LOCAL', 'GHN_STAGING'));
ALTER TABLE wards ADD CONSTRAINT ck_wards_catalog_source
    CHECK (catalog_source IN ('LOCAL', 'GHN_STAGING'));
ALTER TABLE ship_infos ADD CONSTRAINT ck_ship_infos_catalog_source
    CHECK (address_catalog_source IN ('LOCAL', 'GHN_STAGING'));
ALTER TABLE orders ADD CONSTRAINT ck_orders_address_catalog_source
    CHECK (shipping_address_catalog_source IN ('LOCAL', 'GHN_STAGING'));
ALTER TABLE shipping_quotes ADD CONSTRAINT ck_shipping_quotes_catalog_source
    CHECK (address_catalog_source IN ('LOCAL', 'GHN_STAGING'));
ALTER TABLE order_shipping_snapshots ADD CONSTRAINT ck_order_shipping_snapshots_catalog_source
    CHECK (address_catalog_source IN ('LOCAL', 'GHN_STAGING'));

CREATE INDEX idx_provinces_source_active_name
    ON provinces (catalog_source, is_active, name);
CREATE INDEX idx_wards_source_province_active_name
    ON wards (catalog_source, province_code, is_active, name);

CREATE TABLE administrative_address_catalog_state (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    active_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL'
        CHECK (active_source IN ('LOCAL', 'GHN_STAGING')),
    last_status VARCHAR(16) NOT NULL DEFAULT 'NOT_SYNCED'
        CHECK (last_status IN ('NOT_SYNCED', 'READY', 'FAILED')),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    province_count INTEGER NOT NULL DEFAULT 0 CHECK (province_count >= 0),
    ward_count INTEGER NOT NULL DEFAULT 0 CHECK (ward_count >= 0),
    last_error VARCHAR(500)
);

INSERT INTO administrative_address_catalog_state(singleton_id)
VALUES (1)
ON CONFLICT (singleton_id) DO NOTHING;
