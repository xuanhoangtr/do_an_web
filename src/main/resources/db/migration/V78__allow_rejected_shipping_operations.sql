ALTER TABLE shipping_provider_operations
    DROP CONSTRAINT IF EXISTS shipping_provider_operations_status_check;

ALTER TABLE shipping_provider_operations
    ADD CONSTRAINT shipping_provider_operations_status_check
    CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','AMBIGUOUS','DEAD','REJECTED'));
