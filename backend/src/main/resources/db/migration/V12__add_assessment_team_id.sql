DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments ADD COLUMN IF NOT EXISTS team_id VARCHAR(255);
    CREATE INDEX IF NOT EXISTS idx_assessments_teamid ON assessments (team_id);
  END IF;
END $$;
