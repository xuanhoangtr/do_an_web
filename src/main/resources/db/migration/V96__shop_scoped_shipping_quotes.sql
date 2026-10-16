-- Shop-scoped quotes bind one selected cart group to the exact verified
-- pickup, GHN account binding, destination route, and carrier revision.
ALTER TABLE shipping_quotes
    ADD COLUMN shop_id UUID REFERENCES shops(id) ON DELETE RESTRICT,
    ADD COLUMN selected_group_fingerprint VARCHAR(64),
    ADD COLUMN pickup_address_revision_id UUID REFERENCES shop_address_revisions(id) ON DELETE RESTRICT,
    ADD COLUMN pickup_address_revision INTEGER,
    ADD COLUMN shipping_binding_id UUID REFERENCES shop_shipping_bindings(id) ON DELETE RESTRICT,
    ADD COLUMN shipping_binding_revision INTEGER,
    ADD COLUMN shipping_provider_account_id UUID REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT,
    ADD COLUMN shipping_provider_account_updated_at TIMESTAMPTZ(6),
    ADD COLUMN shipping_route_id UUID REFERENCES shop_shipping_routes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_shipping_quotes_shop_context CHECK (
        (shop_id IS NULL AND selected_group_fingerprint IS NULL
            AND pickup_address_revision_id IS NULL AND pickup_address_revision IS NULL
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL)
        OR
        (shop_id IS NOT NULL AND selected_group_fingerprint IS NOT NULL
            AND pickup_address_revision_id IS NOT NULL AND pickup_address_revision > 0
            AND shipping_binding_id IS NOT NULL AND shipping_binding_revision > 0
            AND shipping_provider_account_id IS NOT NULL AND shipping_provider_account_updated_at IS NOT NULL
            AND shipping_route_id IS NOT NULL)
    );

CREATE INDEX idx_shipping_quotes_customer_shop_expiry
    ON shipping_quotes(customer_id, shop_id, expires_at DESC)
    WHERE shop_id IS NOT NULL;

-- Immutable copy of origin and route identity for the eventual order snapshot.
ALTER TABLE order_shipping_snapshots
    ADD COLUMN origin_address_revision_id UUID REFERENCES shop_address_revisions(id) ON DELETE RESTRICT,
    ADD COLUMN origin_address_revision INTEGER,
    ADD COLUMN shipping_binding_id UUID REFERENCES shop_shipping_bindings(id) ON DELETE RESTRICT,
    ADD COLUMN shipping_binding_revision INTEGER,
    ADD COLUMN shipping_provider_account_id UUID REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT,
    ADD COLUMN shipping_provider_account_updated_at TIMESTAMPTZ(6),
    ADD COLUMN shipping_route_id UUID REFERENCES shop_shipping_routes(id) ON DELETE RESTRICT,
    ADD COLUMN selected_group_fingerprint VARCHAR(64),
    ADD CONSTRAINT ck_order_shipping_snapshots_shop_origin CHECK (
        (origin_address_revision_id IS NULL AND origin_address_revision IS NULL
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL
            AND selected_group_fingerprint IS NULL)
        OR
        (origin_address_revision_id IS NOT NULL AND origin_address_revision > 0
            AND shipping_binding_id IS NOT NULL AND shipping_binding_revision > 0
            AND shipping_provider_account_id IS NOT NULL AND shipping_provider_account_updated_at IS NOT NULL
            AND shipping_route_id IS NOT NULL
            AND selected_group_fingerprint IS NOT NULL)
    );
