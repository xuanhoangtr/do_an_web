-- Establish durable group/order and group/payment ownership without assigning
-- synthetic groups to historical orders or payment records.
CREATE TABLE checkout_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_number VARCHAR(40) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ship_info_id UUID REFERENCES ship_infos(id) ON DELETE SET NULL,
    payment_method VARCHAR(40) NOT NULL CHECK (payment_method IN ('COD', 'VNPAY')),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    subtotal BIGINT NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    product_discount_amount BIGINT NOT NULL DEFAULT 0 CHECK (product_discount_amount >= 0),
    voucher_discount_amount BIGINT NOT NULL DEFAULT 0 CHECK (voucher_discount_amount >= 0),
    shipping_fee_before_discount BIGINT NOT NULL DEFAULT 0 CHECK (shipping_fee_before_discount >= 0),
    shipping_discount_amount BIGINT NOT NULL DEFAULT 0 CHECK (shipping_discount_amount >= 0),
    shipping_fee BIGINT NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0),
    total_amount BIGINT NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    payment_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (payment_status IN (
            'PENDING', 'PROCESSING', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED',
            'CANCELLED', 'EXPIRED', 'RECONCILIATION_REQUIRED'
        )),
    lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (lifecycle_status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    fulfillment_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (fulfillment_status IN ('PENDING', 'PROCESSING', 'PARTIALLY_FULFILLED', 'FULFILLED')),
    expires_at TIMESTAMPTZ(6),
    policy_version INTEGER NOT NULL CHECK (policy_version > 0),
    selection_revision VARCHAR(128) NOT NULL CHECK (length(selection_revision) > 0),
    idempotency_key VARCHAR(120) NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-fA-F]{64}$'),
    request_hash_version SMALLINT NOT NULL DEFAULT 1 CHECK (request_hash_version > 0),
    shipping_recipient_name VARCHAR(4096),
    shipping_phone VARCHAR(4096),
    shipping_address VARCHAR(4096),
    shipping_ward VARCHAR(4096),
    shipping_district VARCHAR(4096),
    shipping_province VARCHAR(4096),
    shipping_province_code VARCHAR(20),
    shipping_province_name VARCHAR(255),
    shipping_ward_code VARCHAR(20),
    shipping_ward_name VARCHAR(255),
    shipping_address_catalog_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_checkout_groups_customer_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT uq_checkout_groups_id_customer UNIQUE (id, customer_id),
    CONSTRAINT ck_checkout_groups_merchandise_discounts CHECK (
        product_discount_amount + voucher_discount_amount <= subtotal
    ),
    CONSTRAINT ck_checkout_groups_shipping_discount CHECK (
        shipping_discount_amount <= shipping_fee_before_discount
        AND shipping_fee_before_discount - shipping_discount_amount = shipping_fee
    ),
    CONSTRAINT ck_checkout_groups_total CHECK (
        subtotal - product_discount_amount - voucher_discount_amount + shipping_fee = total_amount
    )
);
CREATE INDEX idx_checkout_groups_customer_created
    ON checkout_groups(customer_id, created_at DESC);
CREATE INDEX idx_checkout_groups_payment_expiry
    ON checkout_groups(payment_status, expires_at);

ALTER TABLE orders
    ADD COLUMN checkout_group_id UUID,
    ADD COLUMN customer_note VARCHAR(4096);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_checkout_group_customer
        FOREIGN KEY (checkout_group_id, user_id)
        REFERENCES checkout_groups(id, customer_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_orders_checkout_group_id
        UNIQUE (checkout_group_id, id);

CREATE INDEX idx_orders_checkout_group
    ON orders(checkout_group_id, created_at);
CREATE UNIQUE INDEX uq_orders_checkout_group_shop
    ON orders(checkout_group_id, shop_id)
    WHERE checkout_group_id IS NOT NULL;

-- Payment records keep their historical order owner; group-owned gateway
-- attempts and transactions are distinct rows with an explicit XOR owner.
ALTER TABLE payment_attempts
    ALTER COLUMN order_id DROP NOT NULL,
    ADD COLUMN checkout_group_id UUID;
ALTER TABLE payment_attempts
    ADD CONSTRAINT fk_payment_attempts_checkout_group
        FOREIGN KEY (checkout_group_id) REFERENCES checkout_groups(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_payment_attempts_single_owner CHECK (
        (order_id IS NOT NULL) <> (checkout_group_id IS NOT NULL)
    );
CREATE INDEX idx_payment_attempts_checkout_group
    ON payment_attempts(checkout_group_id, created_at DESC);

ALTER TABLE payment_provider_transactions
    ALTER COLUMN order_id DROP NOT NULL,
    ADD COLUMN checkout_group_id UUID;
ALTER TABLE payment_provider_transactions
    ADD CONSTRAINT fk_payment_provider_transactions_checkout_group
        FOREIGN KEY (checkout_group_id) REFERENCES checkout_groups(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_payment_provider_transactions_single_owner CHECK (
        (order_id IS NOT NULL) <> (checkout_group_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_group_provider_transaction_has_no_payment_projection CHECK (
        checkout_group_id IS NULL OR payment_id IS NULL
    ),
    ADD CONSTRAINT uq_payment_provider_transactions_group_id
        UNIQUE (checkout_group_id, id);
CREATE INDEX idx_provider_transactions_checkout_group
    ON payment_provider_transactions(checkout_group_id, created_at);

ALTER TABLE payment_callbacks
    ADD COLUMN checkout_group_id UUID;
ALTER TABLE payment_callbacks
    ADD CONSTRAINT fk_payment_callbacks_checkout_group
        FOREIGN KEY (checkout_group_id) REFERENCES checkout_groups(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_payment_callbacks_at_most_one_owner CHECK (
        NOT (order_id IS NOT NULL AND checkout_group_id IS NOT NULL)
    );
CREATE INDEX idx_payment_callbacks_checkout_group
    ON payment_callbacks(checkout_group_id, received_at DESC);

ALTER TABLE payment_reconciliations
    ALTER COLUMN order_id DROP NOT NULL,
    ADD COLUMN checkout_group_id UUID;
ALTER TABLE payment_reconciliations
    ADD CONSTRAINT fk_payment_reconciliations_checkout_group
        FOREIGN KEY (checkout_group_id) REFERENCES checkout_groups(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_payment_reconciliations_single_owner CHECK (
        (order_id IS NOT NULL) <> (checkout_group_id IS NOT NULL)
    );
CREATE INDEX idx_payment_reconciliations_checkout_group
    ON payment_reconciliations(checkout_group_id, checked_at DESC);

CREATE OR REPLACE FUNCTION enforce_checkout_payment_attempt_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    child_group_id UUID;
BEGIN
    IF NEW.order_id IS NOT NULL THEN
        SELECT checkout_group_id INTO child_group_id
        FROM orders WHERE id = NEW.order_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Payment attempt order % does not exist', NEW.order_id
                USING ERRCODE = '23503';
        END IF;
        IF child_group_id IS NOT NULL THEN
            RAISE EXCEPTION 'Grouped child orders require a checkout-group-owned payment attempt'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_payment_attempts_checkout_owner
    BEFORE INSERT OR UPDATE OF order_id, checkout_group_id ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION enforce_checkout_payment_attempt_owner();

CREATE OR REPLACE FUNCTION enforce_checkout_provider_transaction_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    child_group_id UUID;
    attempt_order_id UUID;
    attempt_group_id UUID;
BEGIN
    IF NEW.order_id IS NOT NULL THEN
        SELECT checkout_group_id INTO child_group_id
        FROM orders WHERE id = NEW.order_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Provider transaction order % does not exist', NEW.order_id
                USING ERRCODE = '23503';
        END IF;
        IF child_group_id IS NOT NULL THEN
            RAISE EXCEPTION 'Grouped child orders require a checkout-group-owned provider transaction'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.attempt_id IS NOT NULL THEN
        SELECT order_id, checkout_group_id INTO attempt_order_id, attempt_group_id
        FROM payment_attempts WHERE id = NEW.attempt_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Payment attempt % does not exist', NEW.attempt_id
                USING ERRCODE = '23503';
        END IF;
        IF attempt_order_id IS DISTINCT FROM NEW.order_id
           OR attempt_group_id IS DISTINCT FROM NEW.checkout_group_id THEN
            RAISE EXCEPTION 'Provider transaction and payment attempt owners must match'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_payment_provider_transactions_checkout_owner
    BEFORE INSERT OR UPDATE OF order_id, checkout_group_id, attempt_id
    ON payment_provider_transactions
    FOR EACH ROW EXECUTE FUNCTION enforce_checkout_provider_transaction_owner();

CREATE OR REPLACE FUNCTION enforce_checkout_callback_attempt_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    attempt_order_id UUID;
    attempt_group_id UUID;
BEGIN
    IF NEW.attempt_id IS NULL THEN
        RETURN NEW;
    END IF;
    -- Invalid/unmatched audit callbacks may retain a known reference without
    -- assigning an owner. Validate attribution only when an owner is recorded.
    IF NEW.order_id IS NULL AND NEW.checkout_group_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT order_id, checkout_group_id INTO attempt_order_id, attempt_group_id
    FROM payment_attempts WHERE id = NEW.attempt_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payment attempt % does not exist', NEW.attempt_id
            USING ERRCODE = '23503';
    END IF;
    IF attempt_order_id IS DISTINCT FROM NEW.order_id
       OR attempt_group_id IS DISTINCT FROM NEW.checkout_group_id THEN
        RAISE EXCEPTION 'Payment callback and payment attempt owners must match'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_payment_callbacks_checkout_owner
    BEFORE INSERT OR UPDATE OF order_id, checkout_group_id, attempt_id
    ON payment_callbacks
    FOR EACH ROW EXECUTE FUNCTION enforce_checkout_callback_attempt_owner();

CREATE OR REPLACE FUNCTION enforce_checkout_reconciliation_attempt_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    attempt_order_id UUID;
    attempt_group_id UUID;
BEGIN
    IF NEW.attempt_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT order_id, checkout_group_id INTO attempt_order_id, attempt_group_id
    FROM payment_attempts WHERE id = NEW.attempt_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payment attempt % does not exist', NEW.attempt_id
            USING ERRCODE = '23503';
    END IF;
    IF attempt_order_id IS DISTINCT FROM NEW.order_id
       OR attempt_group_id IS DISTINCT FROM NEW.checkout_group_id THEN
        RAISE EXCEPTION 'Payment reconciliation and payment attempt owners must match'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_payment_reconciliations_checkout_owner
    BEFORE INSERT OR UPDATE OF order_id, checkout_group_id, attempt_id
    ON payment_reconciliations
    FOR EACH ROW EXECUTE FUNCTION enforce_checkout_reconciliation_attempt_owner();

CREATE OR REPLACE FUNCTION prevent_checkout_group_reassignment()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.checkout_group_id IS DISTINCT FROM OLD.checkout_group_id THEN
        RAISE EXCEPTION 'Order checkout-group ownership is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_orders_checkout_group_immutable
    BEFORE UPDATE OF checkout_group_id ON orders
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_group_reassignment();

CREATE TABLE checkout_payment_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capture_transaction_id UUID NOT NULL,
    group_id UUID NOT NULL REFERENCES checkout_groups(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_checkout_payment_allocations_capture_group
        FOREIGN KEY (group_id, capture_transaction_id)
        REFERENCES payment_provider_transactions(checkout_group_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_checkout_payment_allocations_group_order
        FOREIGN KEY (group_id, order_id)
        REFERENCES orders(checkout_group_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_checkout_payment_allocations_capture_order
        UNIQUE (capture_transaction_id, order_id),
    CONSTRAINT uq_checkout_payment_allocations_group_order
        UNIQUE (group_id, order_id),
    CONSTRAINT uq_checkout_payment_allocations_order_id
        UNIQUE (order_id, id)
);
CREATE INDEX idx_checkout_payment_allocations_group
    ON checkout_payment_allocations(group_id, created_at);
CREATE INDEX idx_checkout_payment_allocations_capture
    ON checkout_payment_allocations(capture_transaction_id);

ALTER TABLE payments
    ADD COLUMN checkout_payment_allocation_id UUID;
CREATE UNIQUE INDEX uq_payments_checkout_payment_allocation
    ON payments(checkout_payment_allocation_id)
    WHERE checkout_payment_allocation_id IS NOT NULL;
ALTER TABLE payments
    ADD CONSTRAINT ck_group_payment_projection_has_no_capture_reference
        CHECK (checkout_payment_allocation_id IS NULL OR provider_transaction_id IS NULL);
ALTER TABLE payments
    ADD CONSTRAINT fk_payments_checkout_payment_allocation_order
        FOREIGN KEY (order_id, checkout_payment_allocation_id)
        REFERENCES checkout_payment_allocations(order_id, id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION prevent_checkout_payment_projection_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.checkout_payment_allocation_id IS NOT NULL THEN
            RAISE EXCEPTION 'Checkout payment projections cannot be deleted; record a correction through finance reconciliation'
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.checkout_payment_allocation_id IS NOT NULL
       AND ROW(NEW.order_id, NEW.amount, NEW.currency, NEW.checkout_payment_allocation_id)
           IS DISTINCT FROM
           ROW(OLD.order_id, OLD.amount, OLD.currency, OLD.checkout_payment_allocation_id) THEN
        RAISE EXCEPTION 'Funded checkout payment projections are immutable; record a correction through finance reconciliation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_payment_projection_immutable
    BEFORE UPDATE OF order_id, amount, currency, checkout_payment_allocation_id OR DELETE
    ON payments
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_payment_projection_rewrite();

CREATE OR REPLACE FUNCTION validate_checkout_payment_allocation_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    capture_amount BIGINT;
    capture_currency VARCHAR(3);
    group_total BIGINT;
    order_total BIGINT;
    order_currency VARCHAR(3);
    capture_type VARCHAR(20);
    capture_status VARCHAR(20);
BEGIN
    PERFORM 1 FROM checkout_groups WHERE id = NEW.group_id FOR UPDATE;

    SELECT amount, currency, transaction_type, status
      INTO capture_amount, capture_currency, capture_type, capture_status
      FROM payment_provider_transactions
     WHERE id = NEW.capture_transaction_id AND checkout_group_id = NEW.group_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Checkout allocation capture is not owned by its group'
            USING ERRCODE = '23514';
    END IF;
    IF capture_type <> 'CAPTURE' OR capture_status <> 'SUCCEEDED' THEN
        RAISE EXCEPTION 'Checkout payment allocations require a verified capture'
            USING ERRCODE = '23514';
    END IF;

    SELECT total_amount INTO group_total FROM checkout_groups WHERE id = NEW.group_id FOR UPDATE;
    SELECT total_amount, currency INTO order_total, order_currency
      FROM orders WHERE id = NEW.order_id AND checkout_group_id = NEW.group_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Checkout allocation order is not owned by its group'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.amount <> order_total OR NEW.currency <> order_currency
       OR capture_currency <> NEW.currency OR capture_amount <> group_total THEN
        RAISE EXCEPTION 'Checkout payment allocation amount/currency does not match its verified capture and order'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_payment_allocations_insert_integrity
    BEFORE INSERT ON checkout_payment_allocations
    FOR EACH ROW EXECUTE FUNCTION validate_checkout_payment_allocation_insert();

CREATE OR REPLACE FUNCTION validate_checkout_payment_allocations_complete()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    group_total BIGINT;
    capture_amount BIGINT;
    capture_currency VARCHAR(3);
    allocated_total BIGINT;
    child_total BIGINT;
    allocation_count BIGINT;
    child_count BIGINT;
BEGIN
    SELECT total_amount INTO group_total FROM checkout_groups WHERE id = NEW.group_id FOR UPDATE;
    SELECT amount, currency INTO capture_amount, capture_currency
      FROM payment_provider_transactions
     WHERE id = NEW.capture_transaction_id
       AND checkout_group_id = NEW.group_id
       AND transaction_type = 'CAPTURE'
       AND status = 'SUCCEEDED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Checkout payment allocation requires a verified group capture'
            USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(SUM(amount), 0), COUNT(*)
      INTO allocated_total, allocation_count
      FROM checkout_payment_allocations
     WHERE group_id = NEW.group_id
       AND capture_transaction_id = NEW.capture_transaction_id;
    SELECT COALESCE(SUM(total_amount), 0), COUNT(*)
      INTO child_total, child_count
      FROM orders WHERE checkout_group_id = NEW.group_id;

    IF allocated_total <> capture_amount
       OR allocated_total <> group_total
       OR child_total <> group_total
       OR allocation_count <> child_count THEN
        RAISE EXCEPTION 'Checkout allocations must fund every child order and sum to the verified group capture'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM checkout_payment_allocations a
          LEFT JOIN payments p
            ON p.order_id = a.order_id
           AND p.checkout_payment_allocation_id = a.id
           AND p.amount = a.amount
           AND p.currency = a.currency
         WHERE a.group_id = NEW.group_id
           AND a.capture_transaction_id = NEW.capture_transaction_id
           AND p.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Each checkout payment allocation must link its child payment projection'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_checkout_payment_allocations_complete
    AFTER INSERT ON checkout_payment_allocations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_checkout_payment_allocations_complete();

CREATE OR REPLACE FUNCTION prevent_checkout_payment_allocation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Checkout payment allocations are append-only; record a correction through finance reconciliation'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_checkout_payment_allocations_append_only
    BEFORE UPDATE OR DELETE ON checkout_payment_allocations
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_payment_allocation_mutation();

CREATE OR REPLACE FUNCTION prevent_allocated_capture_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM checkout_payment_allocations WHERE capture_transaction_id = OLD.id) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Verified checkout capture evidence is immutable after allocation'
                USING ERRCODE = '23514';
        END IF;
        IF ROW(NEW.order_id, NEW.checkout_group_id, NEW.attempt_id, NEW.provider,
               NEW.provider_transaction_id, NEW.transaction_type, NEW.amount, NEW.currency,
               NEW.status, NEW.idempotency_key)
           IS DISTINCT FROM
           ROW(OLD.order_id, OLD.checkout_group_id, OLD.attempt_id, OLD.provider,
               OLD.provider_transaction_id, OLD.transaction_type, OLD.amount, OLD.currency,
               OLD.status, OLD.idempotency_key) THEN
            RAISE EXCEPTION 'Verified checkout capture evidence is immutable after allocation'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_allocated_capture_immutable
    BEFORE UPDATE OF order_id, checkout_group_id, attempt_id, provider,
        provider_transaction_id, transaction_type, amount, currency, status, idempotency_key OR DELETE
    ON payment_provider_transactions
    FOR EACH ROW EXECUTE FUNCTION prevent_allocated_capture_rewrite();

CREATE OR REPLACE FUNCTION prevent_checkout_child_insert_after_funding()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.checkout_group_id IS NOT NULL THEN
        PERFORM 1 FROM checkout_groups WHERE id = NEW.checkout_group_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM checkout_payment_allocations WHERE group_id = NEW.checkout_group_id) THEN
            RAISE EXCEPTION 'A funded checkout group cannot acquire another child order'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_child_insert_after_funding
    BEFORE INSERT ON orders
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_child_insert_after_funding();

CREATE OR REPLACE FUNCTION prevent_checkout_group_money_rewrite_after_allocation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM checkout_payment_allocations WHERE group_id = OLD.id)
       AND ROW(NEW.subtotal, NEW.product_discount_amount, NEW.voucher_discount_amount,
               NEW.shipping_fee_before_discount, NEW.shipping_discount_amount, NEW.shipping_fee, NEW.total_amount)
           IS DISTINCT FROM
           ROW(OLD.subtotal, OLD.product_discount_amount, OLD.voucher_discount_amount,
               OLD.shipping_fee_before_discount, OLD.shipping_discount_amount, OLD.shipping_fee, OLD.total_amount) THEN
        RAISE EXCEPTION 'Funded checkout group totals are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_groups_funded_totals_immutable
    BEFORE UPDATE OF subtotal, product_discount_amount, voucher_discount_amount,
        shipping_fee_before_discount, shipping_discount_amount, shipping_fee, total_amount
    ON checkout_groups
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_group_money_rewrite_after_allocation();

CREATE OR REPLACE FUNCTION prevent_checkout_child_money_rewrite_after_allocation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.checkout_group_id IS NOT NULL
       THEN
        PERFORM 1 FROM checkout_groups WHERE id = OLD.checkout_group_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM checkout_payment_allocations WHERE order_id = OLD.id) THEN
            IF ROW(NEW.shop_id, NEW.user_id, NEW.subtotal, NEW.discount_amount, NEW.voucher_discount_amount,
                   NEW.shipping_fee_before_discount, NEW.shipping_discount_amount, NEW.shipping_fee, NEW.total_amount)
               IS DISTINCT FROM
               ROW(OLD.shop_id, OLD.user_id, OLD.subtotal, OLD.discount_amount, OLD.voucher_discount_amount,
                   OLD.shipping_fee_before_discount, OLD.shipping_discount_amount, OLD.shipping_fee, OLD.total_amount) THEN
                RAISE EXCEPTION 'Funded checkout child order ownership and totals are immutable'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_orders_funded_totals_immutable
    BEFORE UPDATE OF subtotal, discount_amount, voucher_discount_amount,
        shipping_fee_before_discount, shipping_discount_amount, shipping_fee, total_amount, shop_id, user_id
    ON orders
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_child_money_rewrite_after_allocation();
