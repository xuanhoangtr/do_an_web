-- Unmatched GHN events have no account id yet. Keep the authenticated provider
-- event id intact while scoping deduplication to the GHN ShopId when available.
-- Existing shipping_events are append-only, so derive their scope from the saved
-- payload in the unique indexes instead of rewriting historical receipts.
ALTER TABLE shipping_events
    ADD COLUMN provider_shop_id VARCHAR(80);

CREATE FUNCTION shipping_event_provider_shop_scope(
    event_provider_code TEXT,
    event_provider_shop_id TEXT,
    event_sanitized_payload JSONB
)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
        NULLIF(BTRIM(event_provider_shop_id), ''),
        CASE WHEN event_provider_code = 'GHN' THEN COALESCE(
            NULLIF(BTRIM(event_sanitized_payload ->> 'ShopId'), ''),
            NULLIF(BTRIM(event_sanitized_payload ->> 'ShopID'), ''),
            NULLIF(BTRIM(event_sanitized_payload ->> 'shop_id'), '')
        ) END
    )
$$;

DROP INDEX IF EXISTS uq_shipping_events_legacy_provider_event;
CREATE UNIQUE INDEX uq_shipping_events_legacy_shop_provider_event
    ON shipping_events(
        provider_code,
        (shipping_event_provider_shop_scope(provider_code, provider_shop_id, sanitized_payload)),
        provider_event_id
    )
    WHERE provider_account_id IS NULL
      AND shipping_event_provider_shop_scope(provider_code, provider_shop_id, sanitized_payload) IS NOT NULL;
CREATE UNIQUE INDEX uq_shipping_events_legacy_provider_event
    ON shipping_events(provider_code, provider_event_id)
    WHERE provider_account_id IS NULL
      AND shipping_event_provider_shop_scope(provider_code, provider_shop_id, sanitized_payload) IS NULL;
