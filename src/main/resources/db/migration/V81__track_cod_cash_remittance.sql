ALTER TABLE cod_obligations
    ADD COLUMN remittance_status VARCHAR(20) NOT NULL DEFAULT 'NOT_DUE'
        CHECK (remittance_status IN ('NOT_DUE','PENDING','RECEIVED')),
    ADD COLUMN remitted_amount BIGINT NOT NULL DEFAULT 0 CHECK (remitted_amount >= 0),
    ADD COLUMN remittance_reference VARCHAR(190),
    ADD COLUMN remittance_note VARCHAR(1000),
    ADD COLUMN remitted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN remitted_at TIMESTAMPTZ(6);

UPDATE cod_obligations
SET remittance_status = 'PENDING'
WHERE actual_collected IS NOT NULL AND actual_collected > 0;

CREATE INDEX idx_cod_obligations_remittance
    ON cod_obligations(remittance_status, collected_at)
    WHERE remittance_status = 'PENDING';
