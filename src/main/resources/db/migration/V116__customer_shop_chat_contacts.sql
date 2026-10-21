-- A contact is created only by a customer opening or sending to a specific shop.
-- Existing SHOP tickets are the only source used to backfill historical contacts.
ALTER TABLE support_tickets
    ADD CONSTRAINT uq_support_tickets_id_shop_customer UNIQUE (id, shop_id, customer_id);

CREATE TABLE customer_shop_chat_contacts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    last_ticket_id UUID,
    first_opened_at TIMESTAMPTZ(6) NOT NULL,
    last_opened_at TIMESTAMPTZ(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_customer_shop_chat_contact_open_order CHECK (first_opened_at <= last_opened_at),
    CONSTRAINT ck_customer_shop_chat_contact_version CHECK (version >= 0),
    CONSTRAINT uq_customer_shop_chat_contact_customer_shop UNIQUE (customer_id, shop_id),
    CONSTRAINT fk_customer_shop_chat_contact_last_ticket
        FOREIGN KEY (last_ticket_id, shop_id, customer_id)
        REFERENCES support_tickets(id, shop_id, customer_id) ON DELETE RESTRICT
);

CREATE INDEX idx_customer_shop_chat_contacts_customer_opened
    ON customer_shop_chat_contacts(customer_id, last_opened_at DESC, id);
CREATE INDEX idx_customer_shop_chat_contacts_shop_customer
    ON customer_shop_chat_contacts(shop_id, customer_id);

WITH eligible_shop_tickets AS (
    SELECT t.*
    FROM support_tickets t
    JOIN users u ON u.id = t.customer_id
    WHERE t.ticket_kind = 'SHOP'
      AND u.status <> 'DELETED'
      AND u.deleted_at IS NULL
), latest_shop_ticket AS (
    SELECT DISTINCT ON (t.customer_id, t.shop_id)
           t.customer_id, t.shop_id, t.id AS last_ticket_id,
           t.last_activity_at AS last_opened_at
    FROM eligible_shop_tickets t
    ORDER BY t.customer_id, t.shop_id, t.last_activity_at DESC, t.id DESC
), contact_times AS (
    SELECT t.customer_id, t.shop_id,
           MIN(t.created_at) AS first_opened_at,
           MAX(t.last_activity_at) AS last_activity_at
    FROM eligible_shop_tickets t
    GROUP BY t.customer_id, t.shop_id
)
INSERT INTO customer_shop_chat_contacts (
    id, customer_id, shop_id, last_ticket_id, first_opened_at, last_opened_at, created_at, updated_at
)
SELECT gen_random_uuid(), times.customer_id, times.shop_id, latest.last_ticket_id,
       times.first_opened_at, GREATEST(times.first_opened_at, latest.last_opened_at),
       times.first_opened_at, times.last_activity_at
FROM contact_times times
JOIN latest_shop_ticket latest USING (customer_id, shop_id);

CREATE OR REPLACE FUNCTION prevent_customer_shop_chat_contact_reparenting()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.customer_id IS DISTINCT FROM NEW.customer_id OR OLD.shop_id IS DISTINCT FROM NEW.shop_id THEN
        RAISE EXCEPTION 'Customer shop chat contact scope is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customer_shop_chat_contact_scope_immutable
    BEFORE UPDATE OF customer_id, shop_id ON customer_shop_chat_contacts
    FOR EACH ROW EXECUTE FUNCTION prevent_customer_shop_chat_contact_reparenting();

CREATE OR REPLACE FUNCTION enforce_customer_shop_chat_contact_last_ticket()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.last_ticket_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM support_tickets t
        WHERE t.id = NEW.last_ticket_id AND t.ticket_kind = 'SHOP'
          AND t.shop_id = NEW.shop_id AND t.customer_id = NEW.customer_id
    ) THEN
        RAISE EXCEPTION 'Customer shop chat contact last ticket scope is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customer_shop_chat_contact_last_ticket_scope
    BEFORE INSERT OR UPDATE OF last_ticket_id, customer_id, shop_id ON customer_shop_chat_contacts
    FOR EACH ROW EXECUTE FUNCTION enforce_customer_shop_chat_contact_last_ticket();

-- Contacts are customer-owned inbox state and may be removed when an
-- approved account-erasure request is completed. SHOP tickets and messages
-- retain their existing support-history policy independently.
