-- Vendor access is a capability on an approved profile, never a user role.
ALTER TYPE "AuditAction" ADD VALUE IF NOT EXISTS 'VENDOR_ROUTE_DENIED';

INSERT INTO vendor_profiles (
    user_id, status, business_type, shop_name, legal_name, contact_email,
    submitted_at, reviewed_at, created_at, updated_at
)
SELECT u.id, 'APPROVED', 'INDIVIDUAL', left(u.display_name, 160), u.display_name, u.email,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP
FROM users u
WHERE u.role = 'VENDOR'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO vendor_profiles (
    user_id, status, business_type, shop_name, legal_name, contact_email,
    submitted_at, reviewed_at, created_at, updated_at
)
SELECT DISTINCT u.id, 'APPROVED', 'INDIVIDUAL', left(u.display_name, 160), u.display_name, u.email,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP,
       COALESCE(u.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP
FROM catalog_products p
JOIN users u ON u.id = p.vendor_id
WHERE p.vendor_id IS NOT NULL
  AND u.role IN ('USER', 'CUSTOMER')
ON CONFLICT (user_id) DO NOTHING;

UPDATE users SET role = 'CUSTOMER' WHERE role = 'VENDOR';
UPDATE roles SET is_active = false WHERE upper(name) = 'VENDOR';

DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'users'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%role%'
    LOOP
        EXECUTE format('ALTER TABLE users DROP CONSTRAINT IF EXISTS %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE users ADD CONSTRAINT users_role_capability_check
    CHECK (role IN ('USER','CUSTOMER','SHIPPER','MANAGER','ADMIN','SUPER_ADMIN'));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE role = 'VENDOR') THEN
        RAISE EXCEPTION 'legacy VENDOR roles remain after capability migration';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_vendor_profiles_approved_user
    ON vendor_profiles(user_id) WHERE status = 'APPROVED';