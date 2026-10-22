-- Record which immutable child-order capture allocation a group-owned refund
-- belongs to. Historical order-owned refunds remain untouched.
ALTER TABLE checkout_payment_allocations
    ADD CONSTRAINT uq_checkout_payment_allocations_group_order_id
        UNIQUE (group_id, order_id, id);

CREATE TABLE checkout_refund_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL,
    order_id UUID NOT NULL,
    refund_transaction_id UUID NOT NULL,
    capture_allocation_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_checkout_refund_allocations_capture
        FOREIGN KEY (group_id, order_id, capture_allocation_id)
        REFERENCES checkout_payment_allocations(group_id, order_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_checkout_refund_allocations_refund_transaction
        FOREIGN KEY (group_id, refund_transaction_id)
        REFERENCES payment_provider_transactions(checkout_group_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_checkout_refund_allocations_group_transaction
        UNIQUE (group_id, refund_transaction_id),
    CONSTRAINT uq_checkout_refund_allocations_capture_transaction
        UNIQUE (capture_allocation_id, refund_transaction_id)
);
CREATE INDEX idx_checkout_refund_allocations_capture
    ON checkout_refund_allocations(capture_allocation_id, refund_transaction_id);
CREATE INDEX idx_checkout_refund_allocations_order
    ON checkout_refund_allocations(group_id, order_id, created_at);

CREATE OR REPLACE FUNCTION require_group_refund_allocation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.checkout_group_id IS NULL OR NEW.transaction_type <> 'REFUND' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM checkout_refund_allocations
         WHERE group_id = NEW.checkout_group_id
           AND refund_transaction_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Every new group-owned REFUND transaction must be attributed to a child capture allocation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_group_refund_allocation_required
    AFTER INSERT ON payment_provider_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_group_refund_allocation();

CREATE OR REPLACE FUNCTION validate_checkout_refund_allocation_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    capture_amount BIGINT;
    capture_currency VARCHAR(3);
    refund_type VARCHAR(20);
    refund_status VARCHAR(20);
    refund_payment_id UUID;
    refund_amount BIGINT;
    refund_currency VARCHAR(3);
    active_refunds BIGINT;
BEGIN
    -- This is the serialization point for child refund reservations. The
    -- application also locks this row before reading the active refund sum.
    SELECT amount, currency
      INTO capture_amount, capture_currency
      FROM checkout_payment_allocations
     WHERE group_id = NEW.group_id
       AND order_id = NEW.order_id
       AND id = NEW.capture_allocation_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Checkout refund attribution must reference the order capture allocation'
            USING ERRCODE = '23514';
    END IF;

    SELECT transaction_type, status, payment_id, amount, currency
      INTO refund_type, refund_status, refund_payment_id, refund_amount, refund_currency
      FROM payment_provider_transactions
     WHERE checkout_group_id = NEW.group_id
       AND id = NEW.refund_transaction_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Checkout refund attribution must reference a refund owned by its group'
            USING ERRCODE = '23514';
    END IF;
    IF refund_type <> 'REFUND' OR refund_payment_id IS NOT NULL THEN
        RAISE EXCEPTION 'Checkout refund attribution requires a group-owned REFUND transaction without a payment projection'
            USING ERRCODE = '23514';
    END IF;
    IF refund_amount <> NEW.amount
       OR refund_currency <> NEW.currency
       OR capture_currency <> NEW.currency
       OR NEW.amount > capture_amount THEN
        RAISE EXCEPTION 'Checkout refund amount and currency must match the provider refund and fit its child capture allocation'
            USING ERRCODE = '23514';
    END IF;

    -- Ambiguous operations stay PENDING or PROCESSING until provider
    -- reconciliation proves success or definitive failure. Only FAILED
    -- releases reserved capacity.
    IF refund_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED') THEN
        SELECT COALESCE(SUM(a.amount), 0)
          INTO active_refunds
          FROM checkout_refund_allocations a
          JOIN payment_provider_transactions t
            ON t.checkout_group_id = a.group_id
           AND t.id = a.refund_transaction_id
         WHERE a.capture_allocation_id = NEW.capture_allocation_id
           AND t.status IN ('PENDING', 'PROCESSING', 'SUCCEEDED');
        IF active_refunds + NEW.amount > capture_amount THEN
            RAISE EXCEPTION 'Checkout refunds cannot exceed the child capture allocation'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkout_refund_allocations_insert_integrity
    BEFORE INSERT ON checkout_refund_allocations
    FOR EACH ROW EXECUTE FUNCTION validate_checkout_refund_allocation_insert();

CREATE OR REPLACE FUNCTION prevent_checkout_refund_allocation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Checkout refund allocations are append-only; record a correction through finance reconciliation'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_checkout_refund_allocations_append_only
    BEFORE UPDATE OR DELETE ON checkout_refund_allocations
    FOR EACH ROW EXECUTE FUNCTION prevent_checkout_refund_allocation_mutation();

CREATE OR REPLACE FUNCTION protect_attributed_checkout_refund_transaction()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    allocation_id UUID;
    capture_amount BIGINT;
    reserved_amount BIGINT;
BEGIN
    SELECT capture_allocation_id
      INTO allocation_id
      FROM checkout_refund_allocations
     WHERE group_id = OLD.checkout_group_id
       AND refund_transaction_id = OLD.id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF ROW(NEW.order_id, NEW.checkout_group_id, NEW.payment_id, NEW.transaction_type,
           NEW.amount, NEW.currency)
       IS DISTINCT FROM
       ROW(OLD.order_id, OLD.checkout_group_id, OLD.payment_id, OLD.transaction_type,
           OLD.amount, OLD.currency) THEN
        RAISE EXCEPTION 'Attributed checkout refund transaction ownership and amount are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'FAILED' AND NEW.status IN ('PENDING', 'PROCESSING', 'SUCCEEDED') THEN
        SELECT amount
          INTO capture_amount
          FROM checkout_payment_allocations
         WHERE id = allocation_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Attributed checkout refund capture allocation no longer exists'
                USING ERRCODE = '23514';
        END IF;
        SELECT COALESCE(SUM(a.amount), 0)
          INTO reserved_amount
          FROM checkout_refund_allocations a
          JOIN payment_provider_transactions t
            ON t.checkout_group_id = a.group_id
           AND t.id = a.refund_transaction_id
         WHERE a.capture_allocation_id = allocation_id
           AND a.refund_transaction_id <> OLD.id
           AND t.status IN ('PENDING', 'PROCESSING', 'SUCCEEDED');
        IF reserved_amount + OLD.amount > capture_amount THEN
            RAISE EXCEPTION 'Checkout refunds cannot exceed the child capture allocation'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_attributed_checkout_refund_transaction_immutable
    BEFORE UPDATE OF order_id, checkout_group_id, payment_id, transaction_type,
        amount, currency, status
    ON payment_provider_transactions
    FOR EACH ROW EXECUTE FUNCTION protect_attributed_checkout_refund_transaction();
