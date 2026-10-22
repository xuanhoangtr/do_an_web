-- Late captures are refunded at the capture level. They must not be given
-- synthetic child allocations because the capture may be a duplicate of an
-- already allocated group payment.
CREATE OR REPLACE FUNCTION require_group_refund_allocation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    has_child_attribution BOOLEAN;
    capture_amount BIGINT;
    capture_currency VARCHAR(3);
    capture_provider VARCHAR(60);
    capture_attempt_id UUID;
    capture_environment VARCHAR(16);
    active_refunds BIGINT;
    other_late_refunds BIGINT;
BEGIN
    IF NEW.checkout_group_id IS NULL OR NEW.transaction_type <> 'REFUND' THEN
        RETURN NEW;
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM checkout_refund_allocations
         WHERE group_id = NEW.checkout_group_id
           AND refund_transaction_id = NEW.id
    ) INTO has_child_attribution;
    IF has_child_attribution THEN
        RETURN NEW;
    END IF;

    IF NEW.idempotency_key NOT LIKE 'late:VNPAY:%'
       OR NEW.provider <> 'VNPAY'
       OR NEW.order_id IS NOT NULL
       OR NEW.payment_id IS NOT NULL
       OR NEW.provider_request_id IS NULL
       OR NEW.attempt_id IS NULL THEN
        RAISE EXCEPTION 'Every group-owned REFUND must have child attribution or identify a verified late VNPAY capture'
            USING ERRCODE = '23514';
    END IF;

    SELECT amount, currency, provider, attempt_id, provider_environment
      INTO capture_amount, capture_currency, capture_provider, capture_attempt_id, capture_environment
      FROM payment_provider_transactions
     WHERE checkout_group_id = NEW.checkout_group_id
       AND transaction_type = 'CAPTURE'
       AND status = 'SUCCEEDED'
       AND provider = 'VNPAY'
       AND provider_transaction_id = NEW.provider_request_id
     FOR UPDATE;
    IF NOT FOUND
       OR capture_provider <> NEW.provider
       OR capture_attempt_id IS DISTINCT FROM NEW.attempt_id
       OR capture_environment IS DISTINCT FROM NEW.provider_environment
       OR capture_currency <> NEW.currency
       OR NEW.amount <= 0
       OR NEW.amount > capture_amount THEN
        RAISE EXCEPTION 'Late group refund must reference a succeeded VNPAY capture in the same group and fit its amount'
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*)
      INTO other_late_refunds
      FROM payment_provider_transactions other_refund
     WHERE other_refund.checkout_group_id = NEW.checkout_group_id
       AND other_refund.transaction_type = 'REFUND'
       AND other_refund.provider_request_id = NEW.provider_request_id
       AND other_refund.idempotency_key LIKE 'late:VNPAY:%'
       AND other_refund.id <> NEW.id
       AND NOT EXISTS (
           SELECT 1 FROM checkout_refund_allocations allocation
            WHERE allocation.group_id = other_refund.checkout_group_id
              AND allocation.refund_transaction_id = other_refund.id
       );
    IF other_late_refunds > 0 THEN
        RAISE EXCEPTION 'A late capture can have only one capture-level refund intent'
            USING ERRCODE = '23505';
    END IF;

    -- Include all child-attributed and late refunds against this capture. Holds
    -- remain reserved while PENDING or PROCESSING and after SUCCEEDED.
    SELECT COALESCE(SUM(amount), 0)
      INTO active_refunds
      FROM payment_provider_transactions
     WHERE checkout_group_id = NEW.checkout_group_id
       AND transaction_type = 'REFUND'
       AND provider_request_id = NEW.provider_request_id
       AND status IN ('PENDING', 'PROCESSING', 'SUCCEEDED');
    IF active_refunds > capture_amount THEN
        RAISE EXCEPTION 'Group refunds cannot exceed the verified capture amount'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION protect_unattributed_late_refund_transaction()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    has_child_attribution BOOLEAN;
    capture_amount BIGINT;
    reserved_amount BIGINT;
BEGIN
    SELECT EXISTS (
        SELECT 1
          FROM checkout_refund_allocations
         WHERE group_id = OLD.checkout_group_id
           AND refund_transaction_id = OLD.id
    ) INTO has_child_attribution;
    IF has_child_attribution OR OLD.checkout_group_id IS NULL
       OR OLD.transaction_type <> 'REFUND' THEN
        RETURN NEW;
    END IF;

    IF ROW(NEW.order_id, NEW.checkout_group_id, NEW.attempt_id, NEW.payment_id,
           NEW.provider, NEW.provider_request_id, NEW.transaction_type,
           NEW.amount, NEW.currency, NEW.provider_environment, NEW.idempotency_key)
       IS DISTINCT FROM
       ROW(OLD.order_id, OLD.checkout_group_id, OLD.attempt_id, OLD.payment_id,
           OLD.provider, OLD.provider_request_id, OLD.transaction_type,
           OLD.amount, OLD.currency, OLD.provider_environment, OLD.idempotency_key) THEN
        RAISE EXCEPTION 'Late capture refund ownership, provider reference, and amount are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'FAILED' AND NEW.status IN ('PENDING', 'PROCESSING', 'SUCCEEDED') THEN
        SELECT amount
          INTO capture_amount
          FROM payment_provider_transactions
         WHERE checkout_group_id = OLD.checkout_group_id
           AND transaction_type = 'CAPTURE'
           AND status = 'SUCCEEDED'
           AND provider = OLD.provider
           AND provider_transaction_id = OLD.provider_request_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Late capture refund no longer references a succeeded capture'
                USING ERRCODE = '23514';
        END IF;
        SELECT COALESCE(SUM(amount), 0)
          INTO reserved_amount
          FROM payment_provider_transactions
         WHERE checkout_group_id = OLD.checkout_group_id
           AND transaction_type = 'REFUND'
           AND provider_request_id = OLD.provider_request_id
           AND id <> OLD.id
           AND status IN ('PENDING', 'PROCESSING', 'SUCCEEDED');
        IF reserved_amount + OLD.amount > capture_amount THEN
            RAISE EXCEPTION 'Group refunds cannot exceed the verified capture amount'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_unattributed_late_refund_immutable
    BEFORE UPDATE OF order_id, checkout_group_id, attempt_id, payment_id,
        provider, provider_request_id, transaction_type, amount, currency,
        provider_environment, idempotency_key, status
    ON payment_provider_transactions
    FOR EACH ROW EXECUTE FUNCTION protect_unattributed_late_refund_transaction();
