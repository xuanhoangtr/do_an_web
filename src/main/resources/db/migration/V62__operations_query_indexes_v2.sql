-- Keep index creation transactional. Concurrent index builds waited on a
-- long-lived transaction's virtualxid during PostgreSQL verification.
CREATE INDEX IF NOT EXISTS idx_support_staff_queue_order_v2
    ON support_tickets(priority DESC, last_activity_at ASC, id)
    WHERE status <> 'CLOSED';

CREATE INDEX IF NOT EXISTS idx_support_staff_page_v2
    ON support_tickets(last_activity_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_support_assignee_page_v2
    ON support_tickets(assigned_agent_id, last_activity_at DESC, id)
    WHERE assigned_agent_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fulfillment_created_page_v2
    ON order_fulfillments(created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_fulfillment_status_page_v2
    ON order_fulfillments(status, created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_fulfillment_vendor_created_page_v2
    ON order_fulfillments(vendor_id, created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_fulfillment_shipper_created_page_v2
    ON order_fulfillments(shipper_id, created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_fulfillment_item_page_v2
    ON order_fulfillment_items(fulfillment_id, order_item_id);
