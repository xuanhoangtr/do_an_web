-- Additive migration; earlier applied migrations remain immutable.
ALTER TABLE return_resolutions ADD COLUMN IF NOT EXISTS manual_reference VARCHAR(190);
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_resolution_manual_reference ON return_resolutions(manual_reference)
    WHERE manual_reference IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_resolution_case ON return_resolutions(return_case_id);
CREATE INDEX IF NOT EXISTS idx_returns_vendor_work_queue ON return_cases(vendor_id,status,created_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_returns_customer_page ON return_cases(customer_id,created_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_returns_staff_status_page ON return_cases(status,created_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_support_staff_queue ON support_tickets(status,priority,last_activity_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_support_assignee_queue ON support_tickets(assigned_agent_id,status,last_activity_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_risk_staff_queue ON risk_events(status,rule_code,created_at DESC,id);
CREATE INDEX IF NOT EXISTS idx_resolution_items_case_item ON return_resolution_items(return_case_item_id);

ALTER TABLE vendor_payout_destinations ADD COLUMN IF NOT EXISTS request_key VARCHAR(190) UNIQUE;
ALTER TABLE vendor_payout_destinations ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_destination_vendor_page ON vendor_payout_destinations(vendor_id,created_at DESC,id);
