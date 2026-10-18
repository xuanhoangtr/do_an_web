-- Vendor accounts remain customer-authorized at the application boundary;
-- shop capabilities are still granted only by an active OWNER membership.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_capability_check;

ALTER TABLE users ADD CONSTRAINT users_role_capability_check
    CHECK (role IN ('USER','CUSTOMER','VENDOR','SHIPPER','MANAGER','ADMIN','SUPER_ADMIN'));
