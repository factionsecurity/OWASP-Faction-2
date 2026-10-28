-- Application-level remediation owner: the internal user who owns fixing an application's
-- findings. New assessments copy it onto their remediation manager when scheduled. Guarded:
-- Flyway runs before Hibernate creates the table on a fresh database, where the entity definition
-- adds the column anyway.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'applications') THEN
    ALTER TABLE applications
        ADD COLUMN IF NOT EXISTS remediation_owner_id VARCHAR(255);

    -- Backfill from each application's most recent assessment, so the remediation manager people
    -- have been picking by hand becomes the application's default. Only internal, non-deleted
    -- users qualify, matching what the application form accepts.
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'users') THEN
      UPDATE applications app
         SET remediation_owner_id = latest.remediation_manager_id
        FROM (
          SELECT DISTINCT ON (a.application_id) a.application_id, a.remediation_manager_id
            FROM assessments a
            JOIN users u ON u.id = a.remediation_manager_id
           WHERE a.deleted_at IS NULL
             AND a.remediation_manager_id IS NOT NULL
             AND u.is_internal IS TRUE
             AND u.deleted_at IS NULL
           ORDER BY a.application_id, a.created_at DESC NULLS LAST
        ) latest
       WHERE app.id = latest.application_id
         AND app.remediation_owner_id IS NULL;
    END IF;
  END IF;
END $$;
