CREATE TABLE support_ticket_participants (
    ticket_id UUID NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ticket_id, user_id)
);
CREATE INDEX idx_support_participants_user ON support_ticket_participants(user_id, joined_at DESC);

CREATE TABLE support_ticket_reads (
    ticket_id UUID NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ(6) NOT NULL,
    PRIMARY KEY (ticket_id, user_id)
);
CREATE INDEX idx_support_reads_user ON support_ticket_reads(user_id, last_read_at DESC);

INSERT INTO support_ticket_participants (ticket_id, user_id)
SELECT DISTINCT t.id, t.assigned_agent_id
FROM support_tickets t
JOIN users u ON u.id = t.assigned_agent_id
WHERE t.assigned_agent_id IS NOT NULL
  AND u.role::text IN ('MANAGER', 'ADMIN', 'SUPER_ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO support_ticket_participants (ticket_id, user_id)
SELECT DISTINCT m.ticket_id, m.author_id
FROM support_messages m
JOIN users u ON u.id = m.author_id
WHERE m.author_id IS NOT NULL
  AND u.role::text IN ('MANAGER', 'ADMIN', 'SUPER_ADMIN')
ON CONFLICT DO NOTHING;
