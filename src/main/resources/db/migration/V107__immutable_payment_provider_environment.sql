ALTER TABLE payment_attempts
    ADD COLUMN provider_environment VARCHAR(16);

ALTER TABLE payment_attempts
    ADD CONSTRAINT ck_payment_attempts_provider_environment
        CHECK (provider_environment IS NULL OR provider_environment IN ('SANDBOX', 'PRODUCTION'));

ALTER TABLE payment_provider_transactions
    ADD COLUMN provider_environment VARCHAR(16);

ALTER TABLE payment_provider_transactions
    ADD CONSTRAINT ck_payment_provider_transactions_environment
        CHECK (provider_environment IS NULL OR provider_environment IN ('SANDBOX', 'PRODUCTION'));

CREATE OR REPLACE FUNCTION prevent_payment_provider_environment_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider_environment IS DISTINCT FROM OLD.provider_environment THEN
        RAISE EXCEPTION 'Payment provider environment is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_payment_attempts_environment_immutable
    BEFORE UPDATE ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION prevent_payment_provider_environment_rewrite();

CREATE TRIGGER trg_payment_provider_transactions_environment_immutable
    BEFORE UPDATE ON payment_provider_transactions
    FOR EACH ROW EXECUTE FUNCTION prevent_payment_provider_environment_rewrite();

CREATE OR REPLACE FUNCTION enforce_payment_provider_transaction_attempt_environment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    attempt_environment VARCHAR(16);
BEGIN
    IF NEW.attempt_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT provider_environment
      INTO attempt_environment
      FROM payment_attempts
     WHERE id = NEW.attempt_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payment attempt % does not exist', NEW.attempt_id;
    END IF;

    IF NEW.provider_environment IS DISTINCT FROM attempt_environment THEN
        RAISE EXCEPTION 'Payment provider transaction environment must match its payment attempt';
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_payment_provider_transactions_attempt_environment
    BEFORE INSERT OR UPDATE OF attempt_id, provider_environment ON payment_provider_transactions
    FOR EACH ROW EXECUTE FUNCTION enforce_payment_provider_transaction_attempt_environment();
