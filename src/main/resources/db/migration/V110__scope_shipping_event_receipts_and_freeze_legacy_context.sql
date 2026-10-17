-- A single provider account can serve multiple GHN ShopIds. Scope authenticated
-- event receipts by the external shop whenever that identity is available.
DROP INDEX IF EXISTS uq_shipping_events_account_provider_event;
CREATE UNIQUE INDEX uq_shipping_events_account_shop_provider_event
    ON shipping_events(
        provider_code,
        provider_account_id,
        (shipping_event_provider_shop_scope(provider_code, provider_shop_id, sanitized_payload)),
        provider_event_id
    )
    WHERE provider_account_id IS NOT NULL
      AND shipping_event_provider_shop_scope(provider_code, provider_shop_id, sanitized_payload) IS NOT NULL;
CREATE UNIQUE INDEX uq_shipping_events_account_provider_event
    ON shipping_events(provider_code, provider_account_id, provider_event_id)
    WHERE provider_account_id IS NOT NULL
      AND shipping_event_provider_shop_scope(provider_code, provider_shop_id, sanitized_payload) IS NULL;

-- Legacy rows remain account-less for their lifetime. Bind tenant context only
-- on INSERT when the immutable CREATE snapshot is first persisted.
CREATE OR REPLACE FUNCTION prevent_shipment_context_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.provider_context_snapshot IS DISTINCT FROM NEW.provider_context_snapshot
       OR OLD.provider_account_id IS DISTINCT FROM NEW.provider_account_id THEN
        RAISE EXCEPTION 'Provider shipment context is immutable';
    END IF;
    RETURN NEW;
END;
$$;
