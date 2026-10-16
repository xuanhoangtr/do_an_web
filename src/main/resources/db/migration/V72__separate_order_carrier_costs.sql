CREATE TABLE order_carrier_costs (
    order_id UUID PRIMARY KEY REFERENCES orders(id) ON DELETE RESTRICT,
    provider_code VARCHAR(40) NOT NULL,
    fee BIGINT NOT NULL CHECK (fee >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    source VARCHAR(20) NOT NULL CHECK (source IN ('ESTIMATE', 'CREATED')),
    checked_at TIMESTAMPTZ(6) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Preserve carrier costs recorded by earlier versions without changing immutable snapshots.
INSERT INTO order_carrier_costs (order_id, provider_code, fee, currency, source, checked_at)
SELECT s.order_id, s.provider_code,
       (s.quote_metadata -> 'carrierCost' ->> 'fee')::BIGINT,
       s.currency,
       s.quote_metadata -> 'carrierCost' ->> 'source',
       s.created_at
FROM order_shipping_snapshots s
WHERE s.provider_code = 'GHN'
  AND jsonb_typeof(s.quote_metadata -> 'carrierCost') = 'object'
  AND (s.quote_metadata -> 'carrierCost' ->> 'fee') ~ '^[0-9]{1,18}$'
  AND (s.quote_metadata -> 'carrierCost' ->> 'source') IN ('ESTIMATE', 'CREATED')
ON CONFLICT (order_id) DO NOTHING;
