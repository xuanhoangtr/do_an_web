-- Manual GHN reconciliation preserves the authenticated event as an immutable
-- receipt and records the verified tenant/shipment linkage separately.
ALTER TABLE fulfillment_shipments
    ADD CONSTRAINT uq_fulfillment_shipments_id_account_shop
    UNIQUE (id, provider_account_id, shop_id);

CREATE TABLE shipping_event_reconciliations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipping_event_id UUID NOT NULL UNIQUE REFERENCES shipping_events(id) ON DELETE RESTRICT,
    shipment_id UUID NOT NULL,
    provider_account_id UUID NOT NULL REFERENCES shipping_provider_accounts(id) ON DELETE RESTRICT,
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    provider_shop_id VARCHAR(80) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reconciled_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shipping_event_reconciliation_provider_shop_nonblank
        CHECK (length(btrim(provider_shop_id)) > 0),
    CONSTRAINT fk_shipping_event_reconciliation_shipment_context
        FOREIGN KEY (shipment_id, provider_account_id, shop_id)
        REFERENCES fulfillment_shipments(id, provider_account_id, shop_id) ON DELETE RESTRICT
);

CREATE INDEX idx_shipping_event_reconciliations_shipment
    ON shipping_event_reconciliations(shipment_id, reconciled_at DESC);

CREATE TRIGGER trg_shipping_event_reconciliations_append_only
    BEFORE UPDATE OR DELETE ON shipping_event_reconciliations FOR EACH ROW
    EXECUTE FUNCTION prevent_shipping_immutable_mutation();
