-- Persist immutable GHN account/origin context on shipments and scope provider identifiers.
ALTER TABLE fulfillment_shipments
    ADD COLUMN provider_account_id UUID REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT,
    ADD COLUMN provider_context_snapshot JSONB;
ALTER TABLE return_shipments
    ADD COLUMN provider_account_id UUID REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT,
    ADD COLUMN provider_context_snapshot JSONB;
ALTER TABLE shipping_events
    ADD COLUMN provider_account_id UUID REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT;
ALTER TABLE shipping_events DROP CONSTRAINT IF EXISTS shipping_events_outcome_check;
ALTER TABLE shipping_events ADD CONSTRAINT shipping_events_outcome_check CHECK (outcome IN (
    'APPLIED','DUPLICATE','IGNORED_STALE','REJECTED_INVALID','UNKNOWN_SHIPMENT',
    'AMBIGUOUS_SHIPMENT','REJECTED_ACCOUNT_MISMATCH'));
ALTER TABLE fulfillment_shipments ADD CONSTRAINT ck_fulfillment_shipment_provider_context CHECK (
    (provider_account_id IS NULL AND provider_context_snapshot IS NULL)
    OR (provider_account_id IS NOT NULL AND provider_context_snapshot IS NOT NULL));
ALTER TABLE return_shipments ADD CONSTRAINT ck_return_shipment_provider_context CHECK (
    (provider_account_id IS NULL AND provider_context_snapshot IS NULL)
    OR (provider_account_id IS NOT NULL AND provider_context_snapshot IS NOT NULL));

DROP INDEX IF EXISTS uq_fulfillment_shipments_provider_id;
CREATE UNIQUE INDEX uq_fulfillment_shipments_provider_legacy
    ON fulfillment_shipments(provider_code, provider_shipment_id)
    WHERE provider_shipment_id IS NOT NULL AND provider_account_id IS NULL;
CREATE UNIQUE INDEX uq_fulfillment_shipments_provider_account
    ON fulfillment_shipments(provider_code, provider_account_id, provider_shipment_id)
    WHERE provider_shipment_id IS NOT NULL AND provider_account_id IS NOT NULL;

ALTER TABLE shipping_events DROP CONSTRAINT IF EXISTS shipping_events_provider_code_provider_event_id_key;
CREATE UNIQUE INDEX uq_shipping_events_legacy_provider_event
    ON shipping_events(provider_code, provider_event_id) WHERE provider_account_id IS NULL;
CREATE UNIQUE INDEX uq_shipping_events_account_provider_event
    ON shipping_events(provider_code, provider_account_id, provider_event_id)
    WHERE provider_account_id IS NOT NULL;
CREATE INDEX idx_shipping_events_admin_unmatched
    ON shipping_events(received_at ASC)
    WHERE outcome IN ('UNKNOWN_SHIPMENT','AMBIGUOUS_SHIPMENT','REJECTED_ACCOUNT_MISMATCH');

CREATE OR REPLACE FUNCTION prevent_shipment_context_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.provider_context_snapshot IS NOT NULL
       AND NEW.provider_context_snapshot IS DISTINCT FROM OLD.provider_context_snapshot THEN
        RAISE EXCEPTION 'Provider shipment context is immutable';
    END IF;
    IF OLD.provider_account_id IS NOT NULL
       AND NEW.provider_account_id IS DISTINCT FROM OLD.provider_account_id THEN
        RAISE EXCEPTION 'Provider shipment account is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fulfillment_shipment_context_immutable
    BEFORE UPDATE ON fulfillment_shipments FOR EACH ROW EXECUTE FUNCTION prevent_shipment_context_rewrite();
CREATE TRIGGER trg_return_shipment_context_immutable
    BEFORE UPDATE ON return_shipments FOR EACH ROW EXECUTE FUNCTION prevent_shipment_context_rewrite();
