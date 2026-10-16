CREATE TABLE shipping_carriers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    provider_code VARCHAR(40) NOT NULL,
    coverage_scope VARCHAR(30) NOT NULL,
    customer_fee BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    default_for_new_orders BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,
    availability_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT shipping_carriers_supported_route_check CHECK (
        (provider_code = 'INTERNAL' AND coverage_scope = 'HCM') OR
        (provider_code = 'GHN' AND coverage_scope = 'OUTSIDE_HCM')
    ),
    CONSTRAINT shipping_carriers_fee_check CHECK (customer_fee BETWEEN 0 AND 1000000000000),
    CONSTRAINT shipping_carriers_default_active_check CHECK (
        NOT default_for_new_orders OR (active AND deleted_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_shipping_carriers_active_default_scope
    ON shipping_carriers (coverage_scope)
    WHERE active = TRUE AND default_for_new_orders = TRUE AND deleted_at IS NULL;

INSERT INTO shipping_carriers
    (code, display_name, description, provider_code, coverage_scope, customer_fee, active, default_for_new_orders)
VALUES
    ('INTERNAL-HCM', 'Giao hàng nội bộ', 'Phí khách trả cho đơn giao nội bộ TP. Hồ Chí Minh.',
     'INTERNAL', 'HCM', 0, TRUE, TRUE),
    ('GHN-OUTSIDE', 'GHN', 'Phí khách trả cho đơn ngoại tỉnh giao qua GHN; cước GHN thực tế được đối soát riêng.',
     'GHN', 'OUTSIDE_HCM', 30000, TRUE, TRUE);

ALTER TABLE shipping_quotes
    ADD COLUMN carrier_id UUID REFERENCES shipping_carriers(id) ON DELETE RESTRICT,
    ADD COLUMN carrier_code VARCHAR(60),
    ADD COLUMN carrier_name VARCHAR(120),
    ADD COLUMN carrier_availability_version BIGINT NOT NULL DEFAULT 0;

UPDATE shipping_quotes q
SET carrier_id = c.id, carrier_code = c.code, carrier_name = c.display_name,
    carrier_availability_version = c.availability_version
FROM shipping_carriers c
WHERE (q.provider_code = c.provider_code AND c.default_for_new_orders = TRUE);

ALTER TABLE order_shipping_snapshots
    ADD COLUMN carrier_id UUID REFERENCES shipping_carriers(id) ON DELETE RESTRICT,
    ADD COLUMN carrier_code VARCHAR(60),
    ADD COLUMN carrier_name VARCHAR(120);

ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'SHIPPING_CARRIER_CHANGED';
