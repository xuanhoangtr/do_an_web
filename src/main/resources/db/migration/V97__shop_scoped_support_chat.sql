-- Existing tickets remain in the platform support audience. New shop tickets
-- are scoped by shop_id, with shop scope propagated to every chat child row.
ALTER TABLE support_tickets
    ADD COLUMN ticket_kind VARCHAR(24) NOT NULL DEFAULT 'PLATFORM_SUPPORT',
    ADD COLUMN shop_id UUID;

ALTER TABLE support_tickets
    ADD CONSTRAINT fk_support_tickets_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_support_tickets_kind_shop CHECK (
        (ticket_kind = 'PLATFORM_SUPPORT' AND shop_id IS NULL)
        OR (ticket_kind = 'SHOP' AND shop_id IS NOT NULL)
    ),
    ADD CONSTRAINT uq_support_tickets_id_shop UNIQUE (id, shop_id);

CREATE INDEX idx_support_shop_queue
    ON support_tickets(shop_id, status, last_activity_at DESC, id)
    WHERE ticket_kind = 'SHOP';

ALTER TABLE support_messages ADD COLUMN shop_id UUID;
ALTER TABLE support_ticket_participants ADD COLUMN shop_id UUID;
ALTER TABLE support_ticket_reads ADD COLUMN shop_id UUID;
ALTER TABLE support_message_attachments ADD COLUMN shop_id UUID;

ALTER TABLE support_messages
    ADD CONSTRAINT uq_support_messages_id_shop UNIQUE (id, shop_id),
    ADD CONSTRAINT fk_support_messages_ticket_shop FOREIGN KEY (ticket_id, shop_id)
        REFERENCES support_tickets(id, shop_id) ON DELETE RESTRICT;
ALTER TABLE support_ticket_participants
    ADD CONSTRAINT fk_support_participants_ticket_shop FOREIGN KEY (ticket_id, shop_id)
        REFERENCES support_tickets(id, shop_id) ON DELETE CASCADE;
ALTER TABLE support_ticket_reads
    ADD CONSTRAINT fk_support_reads_ticket_shop FOREIGN KEY (ticket_id, shop_id)
        REFERENCES support_tickets(id, shop_id) ON DELETE CASCADE;
ALTER TABLE support_message_attachments
    ADD CONSTRAINT fk_support_attachments_message_shop FOREIGN KEY (message_id, shop_id)
        REFERENCES support_messages(id, shop_id) ON DELETE RESTRICT;

-- A ticket's tenant/customer identity is immutable. The unique/composite child
-- FKs protect inserts, while this guard prevents a later update from moving an
-- existing conversation (and its history) between customers or shops.
CREATE OR REPLACE FUNCTION prevent_support_ticket_scope_reparenting()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ticket_kind IS DISTINCT FROM NEW.ticket_kind
       OR OLD.shop_id IS DISTINCT FROM NEW.shop_id
       OR OLD.customer_id IS DISTINCT FROM NEW.customer_id THEN
        RAISE EXCEPTION 'Support ticket scope is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_support_ticket_scope_immutable
    BEFORE UPDATE OF ticket_kind, shop_id, customer_id ON support_tickets
    FOR EACH ROW EXECUTE FUNCTION prevent_support_ticket_scope_reparenting();

CREATE INDEX idx_support_messages_shop_ticket ON support_messages(shop_id, ticket_id, created_at, id)
    WHERE shop_id IS NOT NULL;
CREATE INDEX idx_support_participants_shop_user ON support_ticket_participants(shop_id, user_id, ticket_id)
    WHERE shop_id IS NOT NULL;
CREATE INDEX idx_support_reads_shop_user ON support_ticket_reads(shop_id, user_id, last_read_at DESC)
    WHERE shop_id IS NOT NULL;

-- Composite foreign keys protect mismatched non-null shop ids. These guards
-- also reject a missing shop id on a shop-owned child row (NULL bypasses a
-- composite FK). INTERNAL messages remain a platform-only capability and are
-- explicitly rejected for SHOP tickets; shop APIs query the CUSTOMER audience.
CREATE OR REPLACE FUNCTION enforce_support_chat_scope()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_shop UUID;
    parent_kind VARCHAR(24);
BEGIN
    IF TG_TABLE_NAME = 'support_messages' THEN
        SELECT shop_id, ticket_kind INTO parent_shop, parent_kind
        FROM support_tickets WHERE id = NEW.ticket_id;
        IF NOT FOUND OR NEW.shop_id IS DISTINCT FROM parent_shop THEN
            RAISE EXCEPTION 'Support message scope does not match its ticket';
        END IF;
        IF parent_kind = 'SHOP' AND NEW.visibility = 'INTERNAL' THEN
            RAISE EXCEPTION 'Internal messages are not allowed in shop conversations';
        END IF;
    ELSIF TG_TABLE_NAME = 'support_ticket_participants' OR TG_TABLE_NAME = 'support_ticket_reads' THEN
        SELECT shop_id INTO parent_shop FROM support_tickets WHERE id = NEW.ticket_id;
        IF NOT FOUND OR NEW.shop_id IS DISTINCT FROM parent_shop THEN
            RAISE EXCEPTION 'Support participant/read scope does not match its ticket';
        END IF;
    ELSIF TG_TABLE_NAME = 'support_message_attachments' THEN
        SELECT shop_id INTO parent_shop FROM support_messages WHERE id = NEW.message_id;
        IF NOT FOUND OR NEW.shop_id IS DISTINCT FROM parent_shop THEN
            RAISE EXCEPTION 'Support attachment scope does not match its message';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_support_messages_scope
    BEFORE INSERT OR UPDATE OF ticket_id, shop_id, visibility ON support_messages
    FOR EACH ROW EXECUTE FUNCTION enforce_support_chat_scope();
CREATE TRIGGER trg_support_participants_scope
    BEFORE INSERT OR UPDATE OF ticket_id, shop_id ON support_ticket_participants
    FOR EACH ROW EXECUTE FUNCTION enforce_support_chat_scope();
CREATE TRIGGER trg_support_reads_scope
    BEFORE INSERT OR UPDATE OF ticket_id, shop_id ON support_ticket_reads
    FOR EACH ROW EXECUTE FUNCTION enforce_support_chat_scope();
CREATE TRIGGER trg_support_attachments_scope
    BEFORE INSERT OR UPDATE OF message_id, shop_id ON support_message_attachments
    FOR EACH ROW EXECUTE FUNCTION enforce_support_chat_scope();
