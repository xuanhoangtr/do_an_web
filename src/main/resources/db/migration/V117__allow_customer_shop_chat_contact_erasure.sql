-- Contacts are customer-owned inbox state and are removed after an approved
-- account erasure. The ticket and message rows remain subject to their
-- existing support retention policy.
DROP TRIGGER IF EXISTS trg_customer_shop_chat_contact_retained
    ON customer_shop_chat_contacts;
DROP FUNCTION IF EXISTS prevent_customer_shop_chat_contact_delete();

INSERT INTO privacy_retention_rules
    (category_key, retention_days, trigger_event, disposition, legal_basis, description)
VALUES
    ('SHOP_CHAT_CONTACT', 0, 'APPROVED_DELETION_REQUEST', 'DELETE', 'CONTRACT',
     'Delete customer-owned shop contact indexes while retaining SHOP ticket and message history.')
ON CONFLICT (category_key) DO NOTHING;
