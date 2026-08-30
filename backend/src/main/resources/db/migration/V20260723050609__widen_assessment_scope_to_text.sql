-- The scope field holds rich-text HTML from the editor but was created as
-- varchar(255) (no columnDefinition on the entity), so saving any real scope
-- failed. Widen it to TEXT to match the entity definition.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments
        ALTER COLUMN scope TYPE TEXT;
  END IF;
END $$;
