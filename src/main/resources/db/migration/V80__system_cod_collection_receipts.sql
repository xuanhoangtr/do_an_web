CREATE SEQUENCE cod_collection_receipt_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE cod_obligations
    ADD COLUMN system_receipt_code VARCHAR(32);

CREATE UNIQUE INDEX uq_cod_obligations_system_receipt
    ON cod_obligations(system_receipt_code)
    WHERE system_receipt_code IS NOT NULL;
