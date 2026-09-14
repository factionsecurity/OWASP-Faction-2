-- External users may now belong to several organizations and sub-organizations. The single
-- organization_id column becomes the first entry of organization_ids; sub_organization_ids starts
-- empty. Guarded: Flyway runs before Hibernate creates the table on a fresh database.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'users') THEN
    ALTER TABLE users
        ADD COLUMN IF NOT EXISTS organization_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
        ADD COLUMN IF NOT EXISTS sub_organization_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'users' AND column_name = 'organization_id') THEN
      UPDATE users
         SET organization_ids = jsonb_build_array(organization_id)
       WHERE organization_id IS NOT NULL AND organization_ids = '[]'::jsonb;
      ALTER TABLE users DROP COLUMN organization_id;
    END IF;
    CREATE INDEX IF NOT EXISTS idx_users_organization_ids ON users USING GIN (organization_ids);
    CREATE INDEX IF NOT EXISTS idx_users_sub_organization_ids ON users USING GIN (sub_organization_ids);
  END IF;
END $$;
