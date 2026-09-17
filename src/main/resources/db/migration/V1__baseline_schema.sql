-- =============================================================================
-- UTEWedding Database Schema (PostgreSQL 15+)
-- =============================================================================

-- Extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Custom Types (Enums)
DO $$ BEGIN
    CREATE TYPE "AuditAction" AS ENUM (
        'BANK_ACCOUNT_CHANGED','ENTITLEMENT_GRANTED','ENTITLEMENT_REVOKED',
        'GUEST_TOKEN_REGENERATED','MEDIA_DELETED','ORDER_CANCELLED','ORDER_CREATED',
        'PAYMENT_STATUS_CHANGED','PRODUCT_CREATED','PRODUCT_HIDDEN','PRODUCT_STATUS_CHANGED','PRODUCT_UPDATED',
        'REFRESH_TOKEN_REUSE_DETECTED','SESSION_REVOKED',
        'USER_DELETED','USER_EMAIL_VERIFIED','USER_LOGIN_FAILED','USER_LOGIN_SUCCEEDED',
        'USER_LOGOUT_ALL','USER_PASSWORD_CHANGED','USER_PASSWORD_RESET','USER_REGISTERED',
        'WEDDING_CREATED','WEDDING_DELETED','WEDDING_PUBLICATION_RESTORED','WEDDING_PUBLISHED',
        'WEDDING_SLUG_CHANGED','WEDDING_SUSPENDED','WEDDING_UNPUBLISHED','WEDDING_UNSUSPENDED',
        'WEDDING_UPDATED','WISH_MODERATED','ORDER_STATUS_CHANGED'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "MediaAssetKind" AS ENUM ('AVATAR_BRIDE','AVATAR_GROOM','BANK_QR','COVER','GALLERY','STORY');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "MediaAssetStatus" AS ENUM ('DELETED','FAILED','PENDING_DELETION','PENDING_UPLOAD','READY');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "WishStatus" AS ENUM ('APPROVED','HIDDEN','PENDING','REJECTED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "CeremonyType" AS ENUM ('LE_DINH_HON','OTHER','THANH_HON','TIEC_BAO_HY','TIEC_NHA_GAI','TIEC_NHA_TRAI','VU_QUY');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "OrderStatus" AS ENUM (
        'CANCELLED','CONFIRMED','DELIVERED','EXPIRED','PAID','PENDING',
        'PROCESSING','REFUNDED','RETURNED','SHIPPED','SHIPPING'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "RsvpStatus" AS ENUM ('ATTENDING','NOT_ATTENDING','UNSURE');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "UserRole" AS ENUM ('ADMIN','GUEST','MANAGER','SHIPPER','SUPER_ADMIN','USER','VENDOR');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "UserStatus" AS ENUM ('ACTIVE','DELETED','SUSPENDED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "EventType" AS ENUM ('ANNIVERSARY','BIRTHDAY','ENGAGEMENT','WEDDING');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE "WeddingStatus" AS ENUM ('ARCHIVED','DRAFT','PUBLISHED','READY','SUSPENDED','UNPUBLISHED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 2. Tables

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name VARCHAR(120) NOT NULL,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER','CUSTOMER','VENDOR','SHIPPER','MANAGER','ADMIN','SUPER_ADMIN')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    email_verified BOOLEAN NOT NULL DEFAULT false,
    email_verified_at TIMESTAMPTZ(6),
    password_changed_at TIMESTAMPTZ(6),
    password_version INTEGER NOT NULL DEFAULT 0,
    last_login_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_users_status_created_at ON users (status, created_at);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at);

CREATE TABLE IF NOT EXISTS roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(60) NOT NULL UNIQUE,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    generation INTEGER NOT NULL DEFAULT 0,
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    expires_at TIMESTAMPTZ(6) NOT NULL,
    revoked_at TIMESTAMPTZ(6),
    revoked_reason VARCHAR(120),
    rotated_at TIMESTAMPTZ(6),
    last_used_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    request_ip VARCHAR(64),
    expires_at TIMESTAMPTZ(6) NOT NULL,
    used_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_used ON password_reset_tokens (user_id, used_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);

CREATE TABLE IF NOT EXISTS categories (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    parent_id BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    name VARCHAR(120) NOT NULL UNIQUE,
    slug VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(1000),
    image_url VARCHAR(2048),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_categories_parent_active ON categories (parent_id, is_active);
CREATE INDEX IF NOT EXISTS idx_categories_display_order ON categories (display_order);

-- Legacy source retained for migration; catalog_products below is canonical.
CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id BIGINT NOT NULL REFERENCES categories(id),
    vendor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE,
    sku VARCHAR(80) NOT NULL UNIQUE,
    short_description VARCHAR(500),
    description VARCHAR(4000),
    customization_guide VARCHAR(2000),
    unit_price NUMERIC(19,2) NOT NULL,
    compare_at_price NUMERIC(19,2),
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    min_order_quantity INTEGER NOT NULL DEFAULT 1,
    weight_grams INTEGER,
    dimensions VARCHAR(160),
    material VARCHAR(160),
    is_featured BOOLEAN NOT NULL DEFAULT false,
    discount_percent INTEGER NOT NULL DEFAULT 0,
    is_customizable BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    image_url VARCHAR(2048),
    average_rating NUMERIC(3,2) DEFAULT 5.00,
    review_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_products_category_active ON products (category_id, is_active);
CREATE INDEX IF NOT EXISTS idx_products_vendor_active ON products (vendor_id, is_active);

CREATE TABLE IF NOT EXISTS product_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    image_url VARCHAR(2048) NOT NULL,
    alt_text VARCHAR(300),
    media_type VARCHAR(40) DEFAULT 'IMAGE',
    display_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS catalog_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id BIGINT REFERENCES categories(id),
    vendor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(4000),
    image_url VARCHAR(2048),
    material VARCHAR(160),
    unit_price BIGINT NOT NULL,
    discount_percent INTEGER NOT NULL DEFAULT 0,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    review_count INTEGER NOT NULL DEFAULT 0,
    average_rating NUMERIC(3,2) DEFAULT 5.00,
    is_featured BOOLEAN NOT NULL DEFAULT false,
    active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_catalog_products_category_active ON catalog_products (category_id, active);
CREATE INDEX IF NOT EXISTS idx_catalog_products_vendor_active ON catalog_products (vendor_id, active);
CREATE INDEX IF NOT EXISTS idx_catalog_products_created_at ON catalog_products (created_at);

ALTER TABLE product_images
    ADD CONSTRAINT fk_product_images_catalog_product
    FOREIGN KEY (product_id) REFERENCES catalog_products(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS carts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cart_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES catalog_products(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price NUMERIC(19,2) NOT NULL,
    customization_text VARCHAR(2000),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cart_items_user_product UNIQUE (user_id, product_id)
);
CREATE INDEX IF NOT EXISTS idx_cart_items_user ON cart_items (user_id);

CREATE TABLE IF NOT EXISTS ship_infos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label VARCHAR(60),
    recipient_name VARCHAR(160) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    address_line VARCHAR(500) NOT NULL,
    ward VARCHAR(120),
    district VARCHAR(120),
    province VARCHAR(120),
    postal_code VARCHAR(20),
    note VARCHAR(1000),
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key VARCHAR(80) NOT NULL UNIQUE,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_premium BOOLEAN NOT NULL DEFAULT false,
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS weddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template_id UUID REFERENCES templates(id) ON DELETE SET NULL,
    slug VARCHAR(60) UNIQUE,
    preview_token VARCHAR(64) NOT NULL UNIQUE,
    internal_title VARCHAR(160),
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('WEDDING','ENGAGEMENT','ANNIVERSARY','BIRTHDAY')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT','READY','PUBLISHED','UNPUBLISHED','ARCHIVED','SUSPENDED')),
    suspended_reason VARCHAR(500),
    version INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ(6),
    unpublished_at TIMESTAMPTZ(6),
    suspended_at TIMESTAMPTZ(6),
    publication_expiry_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_weddings_owner_deleted_at ON weddings (owner_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_weddings_owner_status ON weddings (owner_id, status);
CREATE INDEX IF NOT EXISTS idx_weddings_status_published_at ON weddings (status, published_at);
CREATE INDEX IF NOT EXISTS idx_weddings_deleted_at ON weddings (deleted_at);

CREATE TABLE IF NOT EXISTS wedding_drafts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL UNIQUE REFERENCES weddings(id) ON DELETE CASCADE,
    bride_name VARCHAR(120),
    bride_short_name VARCHAR(60),
    bride_intro VARCHAR(2000),
    bride_family_address VARCHAR(500),
    groom_name VARCHAR(120),
    groom_short_name VARCHAR(60),
    groom_intro VARCHAR(2000),
    groom_family_address VARCHAR(500),
    headline VARCHAR(200),
    invitation_message VARCHAR(3000),
    thank_you_message VARCHAR(2000),
    footer_message VARCHAR(1000),
    family_note VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS wedding_ceremonies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL REFERENCES weddings(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL CHECK (type IN ('VU_QUY','THANH_HON','TIEC_NHA_GAI','TIEC_NHA_TRAI','TIEC_BAO_HY','LE_DINH_HON','OTHER')),
    title VARCHAR(160) NOT NULL,
    venue_name VARCHAR(200),
    venue_address VARCHAR(500),
    google_maps_url VARCHAR(2048),
    description VARCHAR(2000),
    start_date_time TIMESTAMPTZ(6) NOT NULL,
    end_date_time TIMESTAMPTZ(6),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_visible BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ceremonies_wedding_display_order ON wedding_ceremonies (wedding_id, display_order);
CREATE INDEX IF NOT EXISTS idx_ceremonies_wedding_start_time ON wedding_ceremonies (wedding_id, start_date_time);

CREATE TABLE IF NOT EXISTS wedding_section_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL UNIQUE REFERENCES weddings(id) ON DELETE CASCADE,
    show_story BOOLEAN NOT NULL DEFAULT true,
    show_gallery BOOLEAN NOT NULL DEFAULT true,
    show_rsvp BOOLEAN NOT NULL DEFAULT true,
    show_guestbook BOOLEAN NOT NULL DEFAULT true,
    show_countdown BOOLEAN NOT NULL DEFAULT true,
    show_bank_accounts BOOLEAN NOT NULL DEFAULT true,
    primary_color VARCHAR(7) NOT NULL DEFAULT '#ae2919',
    secondary_color VARCHAR(7) NOT NULL DEFAULT '#fdf6f0',
    font_heading VARCHAR(60) NOT NULL DEFAULT 'Playfair Display',
    font_body VARCHAR(60) NOT NULL DEFAULT 'Be Vietnam Pro',
    background_music VARCHAR(60),
    opening_effect VARCHAR(60) NOT NULL DEFAULT 'FADE_IN'
);

CREATE TABLE IF NOT EXISTS guests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL REFERENCES weddings(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    salutation VARCHAR(60),
    phone VARCHAR(40),
    email VARCHAR(320),
    group_name VARCHAR(120),
    guest_token_hash VARCHAR(255) NOT NULL UNIQUE,
    max_attendees INTEGER NOT NULL DEFAULT 1,
    invitation_message_override VARCHAR(2000),
    open_count INTEGER NOT NULL DEFAULT 0,
    first_opened_at TIMESTAMPTZ(6),
    last_opened_at TIMESTAMPTZ(6),
    sent_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_guests_wedding_deleted_at ON guests (wedding_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_guests_wedding_group_name ON guests (wedding_id, group_name);
CREATE INDEX IF NOT EXISTS idx_guests_wedding_created_at ON guests (wedding_id, created_at);

CREATE TABLE IF NOT EXISTS rsvps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL REFERENCES weddings(id) ON DELETE CASCADE,
    guest_id UUID NOT NULL UNIQUE REFERENCES guests(id) ON DELETE CASCADE,
    guest_name VARCHAR(160),
    phone VARCHAR(40),
    attendance_status "RsvpStatus" NOT NULL,
    attendee_count INTEGER NOT NULL DEFAULT 1,
    dietary_notes VARCHAR(500),
    note VARCHAR(1000),
    submitted_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rsvps_wedding_status ON rsvps (wedding_id, attendance_status);
CREATE INDEX IF NOT EXISTS idx_rsvps_wedding_submitted_at ON rsvps (wedding_id, submitted_at);

CREATE TABLE IF NOT EXISTS wishes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL REFERENCES weddings(id) ON DELETE CASCADE,
    guest_id UUID REFERENCES guests(id) ON DELETE SET NULL,
    sender_name VARCHAR(160) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    status "WishStatus" NOT NULL DEFAULT 'APPROVED',
    moderated_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    submitter_ip_hash VARCHAR(64),
    submitted_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ(6)
);
CREATE INDEX IF NOT EXISTS idx_wishes_wedding_status_deleted_at ON wishes (wedding_id, status, deleted_at);
CREATE INDEX IF NOT EXISTS idx_wishes_wedding_submitted_at ON wishes (wedding_id, submitted_at);

CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number VARCHAR(40) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id),
    wedding_id UUID REFERENCES weddings(id),
    ship_info_id UUID REFERENCES ship_infos(id),
    plan_id UUID,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PAID','CONFIRMED','PROCESSING',
        'SHIPPING','SHIPPED','DELIVERED','CANCELLED','EXPIRED','REFUNDED','RETURNED')),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    subtotal BIGINT NOT NULL DEFAULT 0,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    total_amount BIGINT NOT NULL DEFAULT 0,
    subtotal_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    shipping_fee NUMERIC(19,2) NOT NULL DEFAULT 0,
    amount INTEGER NOT NULL DEFAULT 0,
    payment_method VARCHAR(40),
    discount_code VARCHAR(80),
    idempotency_key VARCHAR(120) UNIQUE,
    tracking_number VARCHAR(120),
    shipping_recipient_name VARCHAR(160),
    shipping_phone VARCHAR(40),
    shipping_address VARCHAR(500),
    note VARCHAR(1000),
    paid_at TIMESTAMPTZ(6),
    cancelled_at TIMESTAMPTZ(6),
    expires_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_orders_user_status ON orders (user_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_wedding_status ON orders (wedding_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_status_created_at ON orders (status, created_at);

CREATE TABLE IF NOT EXISTS order_details (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES catalog_products(id),
    product_name VARCHAR(160) NOT NULL,
    product_image_url VARCHAR(2048),
    sku VARCHAR(80),
    unit_price NUMERIC(19,2) NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    line_total NUMERIC(19,2) NOT NULL,
    customization_text VARCHAR(2000),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    product_name VARCHAR(120) NOT NULL,
    unit_price BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    quantity INTEGER NOT NULL DEFAULT 1,
    line_total BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    provider VARCHAR(60) NOT NULL,
    provider_transaction_id VARCHAR(190) UNIQUE,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','REFUNDED')),
    failure_reason VARCHAR(500),
    processed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS media_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wedding_id UUID NOT NULL REFERENCES weddings(id) ON DELETE CASCADE,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    mime_type VARCHAR(80) NOT NULL,
    kind "MediaAssetKind" NOT NULL,
    status "MediaAssetStatus" NOT NULL DEFAULT 'READY',
    byte_size INTEGER NOT NULL DEFAULT 0,
    width INTEGER,
    height INTEGER,
    alt_text VARCHAR(300),
    caption VARCHAR(500),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_visible BOOLEAN NOT NULL DEFAULT true,
    deletion_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action "AuditAction" NOT NULL,
    actor_id UUID,
    actor_role VARCHAR(30),
    target_id UUID,
    target_type VARCHAR(60),
    user_agent VARCHAR(512),
    ip_hash VARCHAR(64),
    request_id VARCHAR(64),
    metadata JSONB,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_created_at ON audit_logs (actor_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target_created_at ON audit_logs (target_type, target_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created_at ON audit_logs (action, created_at);
