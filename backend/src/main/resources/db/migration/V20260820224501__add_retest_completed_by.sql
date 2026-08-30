-- Who verified a retest, stamped once at completion. Separate from last_updated_by, which a
-- later edit overwrites — the retest activity log has to keep crediting the right person.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'retests') THEN
    ALTER TABLE retests
        ADD COLUMN IF NOT EXISTS completed_by VARCHAR(255);
  END IF;
END $$;
