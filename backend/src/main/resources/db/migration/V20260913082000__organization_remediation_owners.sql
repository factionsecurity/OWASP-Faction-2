-- Organization-level remediation owners: internal users responsible for every finding under the
-- organization's applications. Guarded: Flyway runs before Hibernate creates the table on a fresh
-- database, where the entity definition adds the column anyway.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'organizations') THEN
    ALTER TABLE organizations
        ADD COLUMN IF NOT EXISTS remediation_owner_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
  END IF;
END $$;
