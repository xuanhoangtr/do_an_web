-- Source dataset: https://github.com/ThangLeQuoc/vietnamese-provinces-database
-- Release: v5.2.0. Only non-GIS administrative attributes are imported.

CREATE TABLE IF NOT EXISTS provinces (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    name_en VARCHAR(255),
    full_name VARCHAR(255) NOT NULL,
    full_name_en VARCHAR(255),
    code_name VARCHAR(255),
    postal_code_prefix VARCHAR(255),
    administrative_unit_id INTEGER,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS wards (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    name_en VARCHAR(255),
    full_name VARCHAR(255),
    full_name_en VARCHAR(255),
    code_name VARCHAR(255),
    postal_code VARCHAR(20),
    province_code VARCHAR(20) NOT NULL,
    administrative_unit_id INTEGER,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_wards_province_code
        FOREIGN KEY (province_code) REFERENCES provinces(code)
);

ALTER TABLE wards
    ADD CONSTRAINT uq_wards_code_province
    UNIQUE (code, province_code);

CREATE TABLE IF NOT EXISTS vn_provinces_metadata (
    dataset_version VARCHAR(50) NOT NULL,
    latest_decree VARCHAR(100),
    generated_at TIMESTAMP NOT NULL,
    source VARCHAR(255),
    effective_date DATE,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_provinces_active_name
    ON provinces (is_active, name);

CREATE INDEX IF NOT EXISTS idx_wards_province_active_name
    ON wards (province_code, is_active, name);

ALTER TABLE ship_infos
    ADD COLUMN IF NOT EXISTS province_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS province_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS ward_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS ward_name VARCHAR(255);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shipping_province_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS shipping_province_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS shipping_ward_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS shipping_ward_name VARCHAR(255);

ALTER TABLE ship_infos
    ADD CONSTRAINT fk_ship_infos_province_code
    FOREIGN KEY (province_code) REFERENCES provinces(code);

ALTER TABLE ship_infos
    ADD CONSTRAINT fk_ship_infos_ward_code
    FOREIGN KEY (ward_code) REFERENCES wards(code);

ALTER TABLE ship_infos
    ADD CONSTRAINT fk_ship_infos_ward_province
    FOREIGN KEY (ward_code, province_code) REFERENCES wards(code, province_code);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_shipping_province_code
    FOREIGN KEY (shipping_province_code) REFERENCES provinces(code);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_shipping_ward_code
    FOREIGN KEY (shipping_ward_code) REFERENCES wards(code);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_shipping_ward_province
    FOREIGN KEY (shipping_ward_code, shipping_province_code)
    REFERENCES wards(code, province_code);

CREATE INDEX IF NOT EXISTS idx_ship_infos_province_ward
    ON ship_infos (province_code, ward_code);

CREATE INDEX IF NOT EXISTS idx_orders_shipping_province_ward
    ON orders (shipping_province_code, shipping_ward_code);
