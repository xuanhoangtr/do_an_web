CREATE TABLE shops (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(40) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shops_code_nonblank CHECK (length(btrim(code)) > 0),
    CONSTRAINT ck_shops_slug_nonblank CHECK (length(btrim(slug)) > 0),
    CONSTRAINT ck_shops_display_name_nonblank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_shops_kind CHECK (kind IN ('PLATFORM', 'VENDOR')),
    CONSTRAINT ck_shops_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_shops_version_nonnegative CHECK (version >= 0),
    CONSTRAINT uq_shops_code UNIQUE (code),
    CONSTRAINT uq_shops_slug UNIQUE (slug)
);

CREATE UNIQUE INDEX uq_shops_single_platform ON shops(kind) WHERE kind = 'PLATFORM';
CREATE INDEX idx_shops_status_kind_created ON shops(status, kind, created_at DESC);

CREATE TABLE shop_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    revoked_at TIMESTAMPTZ(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shop_memberships_role CHECK (role IN ('OWNER', 'STAFF')),
    CONSTRAINT ck_shop_memberships_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_shop_memberships_revocation CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT ck_shop_memberships_version_nonnegative CHECK (version >= 0),
    CONSTRAINT uq_shop_memberships_shop_user UNIQUE (shop_id, user_id)
);

CREATE UNIQUE INDEX uq_shop_memberships_active_owner_shop
    ON shop_memberships(shop_id) WHERE role = 'OWNER' AND status = 'ACTIVE';
CREATE UNIQUE INDEX uq_shop_memberships_active_owner_user
    ON shop_memberships(user_id) WHERE role = 'OWNER' AND status = 'ACTIVE';
CREATE INDEX idx_shop_memberships_user_status ON shop_memberships(user_id, status, shop_id);
CREATE INDEX idx_shop_memberships_shop_status ON shop_memberships(shop_id, status, role);

CREATE TABLE shop_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    applicant_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    profile_snapshot_enc TEXT NOT NULL,
    submitted_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewer_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ(6),
    review_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shop_applications_attempt_positive CHECK (attempt_number > 0),
    CONSTRAINT ck_shop_applications_status CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_shop_applications_snapshot_encrypted CHECK (profile_snapshot_enc LIKE 'enc:v1:%'),
    CONSTRAINT ck_shop_applications_review_state CHECK (
        (status = 'SUBMITTED' AND reviewer_user_id IS NULL AND reviewed_at IS NULL)
        OR (status = 'APPROVED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (status = 'REJECTED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL
            AND review_reason IS NOT NULL AND length(btrim(review_reason)) > 0)
        OR (status = 'WITHDRAWN' AND reviewer_user_id IS NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT ck_shop_applications_version_nonnegative CHECK (version >= 0),
    CONSTRAINT uq_shop_applications_attempt UNIQUE (shop_id, attempt_number),
    CONSTRAINT uq_shop_applications_id_shop UNIQUE (id, shop_id)
);

CREATE UNIQUE INDEX uq_shop_applications_one_submitted
    ON shop_applications(shop_id) WHERE status = 'SUBMITTED';
CREATE INDEX idx_shop_applications_status_submitted
    ON shop_applications(status, submitted_at, id);
CREATE INDEX idx_shop_applications_applicant ON shop_applications(applicant_user_id, submitted_at DESC);

CREATE TABLE shop_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    application_id UUID,
    reason VARCHAR(1000),
    occurred_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shop_status_history_previous CHECK (
        previous_status IS NULL OR previous_status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'CLOSED')
    ),
    CONSTRAINT ck_shop_status_history_new CHECK (
        new_status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'CLOSED')
    ),
    CONSTRAINT ck_shop_status_history_transition CHECK (
        previous_status IS NULL OR previous_status <> new_status
    ),
    CONSTRAINT fk_shop_status_history_application_shop
        FOREIGN KEY (application_id, shop_id) REFERENCES shop_applications(id, shop_id) ON DELETE RESTRICT
);

CREATE INDEX idx_shop_status_history_shop_time ON shop_status_history(shop_id, occurred_at DESC, id DESC);
CREATE INDEX idx_shop_status_history_actor_time ON shop_status_history(actor_user_id, occurred_at DESC);
