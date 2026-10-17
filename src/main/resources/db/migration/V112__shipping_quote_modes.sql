-- Add explicit shipping-rate policy while preserving historical quotes and snapshots.
-- Constant defaults avoid row updates on append-only order_shipping_snapshots.
ALTER TABLE shipping_carriers
    ADD COLUMN pricing_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY';

UPDATE shipping_carriers
SET pricing_mode = 'FREE', availability_version = availability_version + 1
WHERE code = 'INTERNAL-HCM';

UPDATE shipping_carriers
SET pricing_mode = 'PROVIDER_QUOTE', availability_version = availability_version + 1
WHERE code = 'GHN-OUTSIDE';

ALTER TABLE shipping_carriers
    ADD CONSTRAINT ck_shipping_carriers_pricing_mode CHECK (
        pricing_mode IN ('LEGACY', 'FREE', 'PROVIDER_QUOTE')
        AND (
            (code = 'INTERNAL-HCM' AND provider_code = 'INTERNAL' AND coverage_scope = 'HCM'
                AND pricing_mode = 'FREE')
            OR (code = 'GHN-OUTSIDE' AND provider_code = 'GHN' AND coverage_scope = 'OUTSIDE_HCM'
                AND pricing_mode = 'PROVIDER_QUOTE')
            OR (code NOT IN ('INTERNAL-HCM', 'GHN-OUTSIDE') AND pricing_mode = 'LEGACY')
        )
    );

ALTER TABLE shop_shipping_bindings
    ADD COLUMN route_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY_ROUTES',
    ADD CONSTRAINT ck_shop_shipping_binding_route_mode
        CHECK (route_mode IN ('LEGACY_ROUTES', 'API_QUOTE'));

ALTER TABLE shop_shipping_bindings
    DROP CONSTRAINT ck_shop_shipping_binding_active,
    DROP CONSTRAINT ck_shop_shipping_binding_verified;

ALTER TABLE shop_shipping_bindings
    ADD CONSTRAINT ck_shop_shipping_binding_active CHECK (
        NOT active OR (status = 'VERIFIED'
            AND (route_mode = 'API_QUOTE' OR route_coverage_verified))
    ),
    ADD CONSTRAINT ck_shop_shipping_binding_verified CHECK (
        (status = 'VERIFIED' AND verified_at IS NOT NULL
            AND (route_mode = 'API_QUOTE' OR route_coverage_verified))
        OR (status <> 'VERIFIED' AND verified_at IS NULL AND NOT active)
    );

ALTER TABLE shipping_quotes
    ADD COLUMN pricing_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN policy_version INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_shipping_quotes_pricing_mode CHECK (
        (pricing_mode = 'LEGACY' AND policy_version = 0)
        OR (pricing_mode = 'FREE' AND provider_code = 'INTERNAL' AND policy_version > 0 AND fee = 0)
        OR (pricing_mode = 'PROVIDER_QUOTE' AND provider_code = 'GHN' AND policy_version > 0)
    );

ALTER TABLE order_shipping_snapshots
    ADD COLUMN pricing_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN policy_version INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_shipping_snapshots_pricing_mode CHECK (
        (pricing_mode = 'LEGACY' AND policy_version = 0)
        OR (pricing_mode = 'FREE' AND provider_code = 'INTERNAL' AND policy_version > 0 AND quoted_fee = 0)
        OR (pricing_mode = 'PROVIDER_QUOTE' AND provider_code = 'GHN' AND policy_version > 0)
    );

ALTER TABLE shipping_quotes
    DROP CONSTRAINT ck_shipping_quotes_shop_context;

ALTER TABLE shipping_quotes
    ADD CONSTRAINT ck_shipping_quotes_shop_context CHECK (
        (shop_id IS NULL
            AND selected_group_fingerprint IS NULL
            AND pickup_address_revision_id IS NULL AND pickup_address_revision IS NULL
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL)
        OR
        (shop_id IS NOT NULL AND pricing_mode = 'LEGACY'
            AND selected_group_fingerprint IS NOT NULL
            AND pickup_address_revision_id IS NOT NULL AND pickup_address_revision > 0
            AND shipping_binding_id IS NOT NULL AND shipping_binding_revision > 0
            AND shipping_provider_account_id IS NOT NULL AND shipping_provider_account_updated_at IS NOT NULL
            AND shipping_route_id IS NOT NULL)
        OR
        (shop_id IS NOT NULL AND pricing_mode = 'FREE' AND provider_code = 'INTERNAL'
            AND selected_group_fingerprint IS NOT NULL
            AND pickup_address_revision_id IS NOT NULL AND pickup_address_revision > 0
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL)
        OR
        (shop_id IS NOT NULL AND pricing_mode = 'PROVIDER_QUOTE' AND provider_code = 'GHN'
            AND selected_group_fingerprint IS NOT NULL
            AND pickup_address_revision_id IS NOT NULL AND pickup_address_revision > 0
            AND shipping_binding_id IS NOT NULL AND shipping_binding_revision > 0
            AND shipping_provider_account_id IS NOT NULL AND shipping_provider_account_updated_at IS NOT NULL)
    );

ALTER TABLE order_shipping_snapshots
    DROP CONSTRAINT ck_order_shipping_snapshots_shop_origin;

ALTER TABLE order_shipping_snapshots
    ADD CONSTRAINT ck_order_shipping_snapshots_shop_origin CHECK (
        (shop_id = '00000000-0000-4000-8000-000000000001'::uuid
            AND origin_address_revision_id IS NULL AND origin_address_revision IS NULL
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL AND selected_group_fingerprint IS NULL)
        OR
        (pricing_mode = 'LEGACY'
            AND origin_address_revision_id IS NULL AND origin_address_revision IS NULL
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL AND selected_group_fingerprint IS NULL)
        OR
        (shop_id <> '00000000-0000-4000-8000-000000000001'::uuid
            AND pricing_mode = 'LEGACY'
            AND origin_address_revision_id IS NOT NULL AND origin_address_revision > 0
            AND shipping_binding_id IS NOT NULL AND shipping_binding_revision > 0
            AND shipping_provider_account_id IS NOT NULL AND shipping_provider_account_updated_at IS NOT NULL
            AND shipping_route_id IS NOT NULL AND selected_group_fingerprint IS NOT NULL)
        OR
        (shop_id <> '00000000-0000-4000-8000-000000000001'::uuid
            AND pricing_mode = 'FREE' AND provider_code = 'INTERNAL'
            AND origin_address_revision_id IS NOT NULL AND origin_address_revision > 0
            AND shipping_binding_id IS NULL AND shipping_binding_revision IS NULL
            AND shipping_provider_account_id IS NULL AND shipping_provider_account_updated_at IS NULL
            AND shipping_route_id IS NULL AND selected_group_fingerprint IS NOT NULL)
        OR
        (shop_id <> '00000000-0000-4000-8000-000000000001'::uuid
            AND pricing_mode = 'PROVIDER_QUOTE' AND provider_code = 'GHN'
            AND origin_address_revision_id IS NOT NULL AND origin_address_revision > 0
            AND shipping_binding_id IS NOT NULL AND shipping_binding_revision > 0
            AND shipping_provider_account_id IS NOT NULL AND shipping_provider_account_updated_at IS NOT NULL
            AND selected_group_fingerprint IS NOT NULL)
    );

-- A CHECK cannot inspect the referenced binding's route mode. Enforce the
-- route-less API_QUOTE exception at the quote/snapshot write boundary.
CREATE OR REPLACE FUNCTION require_api_quote_binding_for_route_less_quote()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE linked_route_mode VARCHAR(24);
BEGIN
    IF NEW.pricing_mode = 'PROVIDER_QUOTE'
       AND NEW.shipping_binding_id IS NOT NULL
       AND NEW.shipping_route_id IS NULL THEN
        SELECT route_mode INTO linked_route_mode
        FROM shop_shipping_bindings
        WHERE id = NEW.shipping_binding_id AND shop_id = NEW.shop_id;

        IF linked_route_mode IS DISTINCT FROM 'API_QUOTE' THEN
            RAISE EXCEPTION 'A route-less provider quote requires an API_QUOTE binding'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_shipping_quotes_api_quote_route_mode
    BEFORE INSERT
    ON shipping_quotes
    FOR EACH ROW EXECUTE FUNCTION require_api_quote_binding_for_route_less_quote();

CREATE TRIGGER trg_shipping_quotes_api_quote_route_mode_update
    BEFORE UPDATE OF pricing_mode, shop_id, shipping_binding_id, shipping_route_id
    ON shipping_quotes
    FOR EACH ROW EXECUTE FUNCTION require_api_quote_binding_for_route_less_quote();

CREATE TRIGGER trg_order_shipping_snapshots_api_quote_route_mode
    BEFORE INSERT ON order_shipping_snapshots
    FOR EACH ROW EXECUTE FUNCTION require_api_quote_binding_for_route_less_quote();
