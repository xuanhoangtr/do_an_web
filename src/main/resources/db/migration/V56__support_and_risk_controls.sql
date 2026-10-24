-- P2 lightweight customer support and rule-based fraud controls.
ALTER TABLE managed_media_objects ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_managed_media_owner ON managed_media_objects(owner_id, created_at DESC)
    WHERE owner_id IS NOT NULL;

CREATE TABLE support_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number VARCHAR(40) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    category VARCHAR(20) NOT NULL CHECK (category IN ('ORDER','PAYMENT','RETURN','SHIPPING','GENERAL')),
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN' CHECK (status IN (
        'OPEN','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED')),
    subject VARCHAR(200) NOT NULL,
    order_id UUID REFERENCES orders(id) ON DELETE RESTRICT,
    payment_id UUID REFERENCES payments(id) ON DELETE RESTRICT,
    return_case_id UUID REFERENCES return_cases(id) ON DELETE RESTRICT,
    return_dispute_id UUID REFERENCES return_disputes(id) ON DELETE RESTRICT,
    fulfillment_id UUID REFERENCES order_fulfillments(id) ON DELETE RESTRICT,
    vendor_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    assigned_agent_id UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ(6),
    closed_at TIMESTAMPTZ(6),
    last_activity_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_support_ticket_open_dispute ON support_tickets(return_dispute_id)
    WHERE return_dispute_id IS NOT NULL AND status <> 'CLOSED';
CREATE INDEX idx_support_customer_status ON support_tickets(customer_id, status, last_activity_at DESC);
CREATE INDEX idx_support_staff_queue ON support_tickets(status, priority, last_activity_at, id)
    WHERE status <> 'CLOSED';
CREATE INDEX idx_support_assignee_queue ON support_tickets(assigned_agent_id, status, last_activity_at DESC)
    WHERE assigned_agent_id IS NOT NULL;
CREATE INDEX idx_support_order ON support_tickets(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_support_payment ON support_tickets(payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX idx_support_return_case ON support_tickets(return_case_id) WHERE return_case_id IS NOT NULL;
CREATE INDEX idx_support_fulfillment ON support_tickets(fulfillment_id) WHERE fulfillment_id IS NOT NULL;
CREATE INDEX idx_support_vendor ON support_tickets(vendor_id) WHERE vendor_id IS NOT NULL;

CREATE TABLE support_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES support_tickets(id) ON DELETE RESTRICT,
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    visibility VARCHAR(20) NOT NULL CHECK (visibility IN ('CUSTOMER','INTERNAL')),
    body VARCHAR(5000) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_support_messages_ticket ON support_messages(ticket_id, created_at, id);
CREATE INDEX idx_support_messages_author ON support_messages(author_id, created_at DESC) WHERE author_id IS NOT NULL;

CREATE TABLE support_message_attachments (
    message_id UUID NOT NULL REFERENCES support_messages(id) ON DELETE RESTRICT,
    managed_media_id UUID NOT NULL REFERENCES managed_media_objects(id) ON DELETE RESTRICT,
    PRIMARY KEY (message_id, managed_media_id)
);
CREATE INDEX idx_support_attachments_media ON support_message_attachments(managed_media_id);

CREATE TABLE support_idempotency (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(190) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    ticket_id UUID REFERENCES support_tickets(id) ON DELETE RESTRICT,
    message_id UUID REFERENCES support_messages(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, idempotency_key),
    CONSTRAINT support_idempotency_result_check CHECK (ticket_id IS NOT NULL OR message_id IS NOT NULL)
);
CREATE INDEX idx_support_idempotency_ticket ON support_idempotency(ticket_id) WHERE ticket_id IS NOT NULL;

CREATE TABLE risk_rule_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_code VARCHAR(60) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mode VARCHAR(20) NOT NULL DEFAULT 'SHADOW' CHECK (mode IN ('SHADOW','ENFORCE')),
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    recommended_action VARCHAR(30) NOT NULL CHECK (recommended_action IN ('ALLOW','REVIEW','BLOCK_ACTION','FLAG')),
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (rule_code, version)
);
CREATE INDEX idx_risk_rules_active ON risk_rule_configs(rule_code, version DESC) WHERE enabled;

CREATE TABLE risk_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_code VARCHAR(60) NOT NULL,
    rule_version INTEGER NOT NULL,
    subject_type VARCHAR(30) NOT NULL,
    subject_id VARCHAR(190) NOT NULL,
    score INTEGER NOT NULL DEFAULT 0 CHECK (score BETWEEN 0 AND 100),
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    recommended_action VARCHAR(30) NOT NULL CHECK (recommended_action IN ('ALLOW','REVIEW','BLOCK_ACTION','FLAG')),
    effective_action VARCHAR(30) NOT NULL CHECK (effective_action IN ('ALLOW','REVIEW','BLOCK_ACTION','FLAG')),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    deduplication_key VARCHAR(190) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DISMISSED','CONFIRMED','RESOLVED')),
    resolution_note VARCHAR(1000),
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_risk_events_queue ON risk_events(status, severity, created_at DESC) WHERE status='OPEN';
CREATE INDEX idx_risk_events_subject ON risk_events(subject_type, subject_id, created_at DESC);
CREATE INDEX idx_risk_events_resolver ON risk_events(resolved_by) WHERE resolved_by IS NOT NULL;

-- Only keyed HMACs are persisted. Raw phone/address/IP values never enter this table.
CREATE TABLE risk_correlations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    voucher_hash CHAR(64),
    phone_hash CHAR(64),
    address_hash CHAR(64),
    occurred_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT risk_correlation_value_check CHECK (
        voucher_hash IS NOT NULL OR phone_hash IS NOT NULL OR address_hash IS NOT NULL)
);
CREATE INDEX idx_risk_correlation_voucher_phone ON risk_correlations(voucher_hash, phone_hash, occurred_at DESC)
    WHERE voucher_hash IS NOT NULL AND phone_hash IS NOT NULL;
CREATE INDEX idx_risk_correlation_voucher_address ON risk_correlations(voucher_hash, address_hash, occurred_at DESC)
    WHERE voucher_hash IS NOT NULL AND address_hash IS NOT NULL;
CREATE INDEX idx_risk_correlation_retention ON risk_correlations(occurred_at);

INSERT INTO risk_rule_configs(rule_code, version, severity, recommended_action, parameters) VALUES
('ORDER_VELOCITY',1,'HIGH','REVIEW','{"limit":5,"windowMinutes":60}'),
('HIGH_VALUE_FIRST_ORDER',1,'MEDIUM','REVIEW','{"amount":5000000}'),
('COD_FAILURES',1,'HIGH','BLOCK_ACTION','{"count":3,"windowDays":90,"durationDays":30}'),
('CANCELLATION_RATE',1,'MEDIUM','REVIEW','{"count":3,"ratePercent":50,"windowDays":30}'),
('RETURN_REFUND_RATE',1,'MEDIUM','REVIEW','{"count":3,"ratePercent":50,"windowDays":90}'),
('VOUCHER_MULTI_ACCOUNT',1,'MEDIUM','REVIEW','{"accounts":3,"windowHours":24}'),
('VENDOR_DISPUTE_RATE',1,'HIGH','REVIEW','{"minimumFulfillments":20,"ratePercent":20,"windowDays":30}'),
('AUTH_RATE_LIMIT',1,'HIGH','FLAG','{}')
ON CONFLICT (rule_code, version) DO NOTHING;

CREATE TRIGGER trg_support_messages_append_only
    BEFORE UPDATE OR DELETE ON support_messages FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();
CREATE TRIGGER trg_risk_events_no_delete
    BEFORE DELETE ON risk_events FOR EACH ROW EXECUTE FUNCTION prevent_shipping_immutable_mutation();

