-- Legacy marketplace tables remain migration data only.
CREATE TABLE IF NOT EXISTS legacy_schema_artifacts (
    table_name VARCHAR(120) PRIMARY KEY,
    canonical_replacement VARCHAR(120) NOT NULL,
    runtime_reads BOOLEAN NOT NULL DEFAULT false,
    notes VARCHAR(500) NOT NULL,
    marked_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO legacy_schema_artifacts (table_name, canonical_replacement, runtime_reads, notes)
VALUES
 ('products', 'catalog_products', false, 'Legacy migration source; Java runtime never writes or reads it.'),
 ('order_details', 'order_items', false, 'Legacy compatibility source; Java runtime uses order_items.')
ON CONFLICT (table_name) DO UPDATE SET canonical_replacement = EXCLUDED.canonical_replacement,
 runtime_reads = EXCLUDED.runtime_reads, notes = EXCLUDED.notes;
DO $$ BEGIN
 IF to_regclass('public.products') IS NOT NULL THEN
  COMMENT ON TABLE products IS 'LEGACY: migration source only. Canonical runtime table is catalog_products.';
 END IF;
 IF to_regclass('public.order_details') IS NOT NULL THEN
  COMMENT ON TABLE order_details IS 'LEGACY: compatibility only. Canonical runtime table is order_items.';
 END IF;
END $$;
