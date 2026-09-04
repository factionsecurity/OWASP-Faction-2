-- Reconciling a shared field (a notebook note, a content template, a default vulnerability) looks
-- up references by field_id alone, because that content belongs to no single assessment. The
-- existing composite index leads with assessment_id, which Postgres cannot use for this predicate,
-- so the lookup was a sequential scan on every save of those three things.
--
-- ddl-auto: update does not add indexes to an existing table, so the entity annotation alone would
-- only take effect on a fresh install.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'inline_image_refs') THEN
    CREATE INDEX IF NOT EXISTS idx_inline_image_refs_fieldid
        ON inline_image_refs (field_id);
  END IF;
END $$;
