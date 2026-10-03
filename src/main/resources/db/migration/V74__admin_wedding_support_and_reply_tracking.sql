ALTER TABLE support_tickets
    ADD COLUMN wedding_id UUID REFERENCES weddings(id) ON DELETE SET NULL,
    ADD COLUMN created_by_id UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_support_wedding ON support_tickets(wedding_id) WHERE wedding_id IS NOT NULL;
CREATE INDEX idx_support_created_by ON support_tickets(created_by_id) WHERE created_by_id IS NOT NULL;
