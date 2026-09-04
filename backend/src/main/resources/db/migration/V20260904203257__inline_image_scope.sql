-- Inline images gained a second kind: library images, owned by a content template or a default
-- vulnerability rather than by one assessment, and readable by any authenticated user.
--
-- An explicit column rather than inferring it from a null assessment_id, because the two scopes
-- differ in who may read them: a bug that left assessment_id unset must not quietly publish a
-- client screenshot. Existing rows are all assessment-owned.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'inline_images') THEN
    ALTER TABLE inline_images
        ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ASSESSMENT';
  END IF;
END $$;
