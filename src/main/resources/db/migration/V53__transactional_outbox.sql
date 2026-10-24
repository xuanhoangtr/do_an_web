CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID,
    deduplication_key VARCHAR(190) NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','DEAD','AMBIGUOUS')),
    correlation_id VARCHAR(120),
    request_id VARCHAR(120),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 8 CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ(6),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_outbox_claimable
    ON outbox_events(next_attempt_at, created_at, id)
    WHERE status IN ('PENDING','FAILED');
CREATE INDEX IF NOT EXISTS idx_outbox_leases
    ON outbox_events(lease_until, id)
    WHERE status = 'PROCESSING';
CREATE INDEX IF NOT EXISTS idx_outbox_operations
    ON outbox_events(event_type, status, created_at);

CREATE TABLE IF NOT EXISTS outbox_consumer_receipts (
    event_id UUID NOT NULL REFERENCES outbox_events(id) ON DELETE RESTRICT,
    consumer_name VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE IF NOT EXISTS email_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_event_id UUID REFERENCES outbox_events(id) ON DELETE RESTRICT,
    deduplication_key VARCHAR(190) NOT NULL UNIQUE,
    recipient VARCHAR(320) NOT NULL,
    email_type VARCHAR(80) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_message_id VARCHAR(190),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','DELIVERED','BOUNCED','FAILED','AMBIGUOUS')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_deliveries_status_due
    ON email_deliveries(status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS email_provider_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(190) NOT NULL,
    provider_message_id VARCHAR(190),
    delivery_id UUID REFERENCES email_deliveries(id) ON DELETE RESTRICT,
    event_type VARCHAR(60) NOT NULL,
    occurred_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, provider_event_id)
);
CREATE INDEX IF NOT EXISTS idx_email_provider_events_delivery ON email_provider_events(delivery_id);

ALTER TABLE user_notifications ADD COLUMN IF NOT EXISTS source_event_id UUID REFERENCES outbox_events(id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_notifications_event_recipient_type
    ON user_notifications(source_event_id, recipient_id, type)
    WHERE source_event_id IS NOT NULL;