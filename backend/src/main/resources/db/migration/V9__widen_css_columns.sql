-- Template CSS can easily exceed 255 characters. Hibernate created these
-- columns as varchar(255) since the entities previously had no @Column
-- annotation, and ddl-auto=update does not alter an existing column's type
-- on its own, so widen them explicitly.
-- Guarded for fresh installs, where the tables don't exist yet at migration
-- time (Flyway runs before Hibernate's schema bootstrap) — there, the
-- entities' @Column(columnDefinition = "TEXT") gives the right type when the
-- tables are first created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'report_templates') THEN
    ALTER TABLE report_templates ALTER COLUMN css TYPE text;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments ALTER COLUMN template_css TYPE text;
  END IF;
END $$;
