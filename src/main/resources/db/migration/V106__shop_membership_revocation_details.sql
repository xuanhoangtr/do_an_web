ALTER TABLE shop_memberships
    ADD COLUMN revoked_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN revocation_reason VARCHAR(1000);

ALTER TABLE shop_memberships
    ADD CONSTRAINT ck_shop_memberships_active_has_no_revocation_details
    CHECK (status <> 'ACTIVE' OR (revoked_by_user_id IS NULL AND revocation_reason IS NULL));
