-- An optional second DOCX on a report template, used for retest reports. It shares the template's
-- CSS, sections, fields and palette; only the document layout differs.
--
-- Guarded for fresh installs, where this Hibernate-owned table doesn't exist yet at migration
-- time (Flyway runs before Hibernate's schema bootstrap) — there, the entity definition creates
-- the columns when the table is created.
--
-- No defaults: a template without a retest document simply has none.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'report_templates') THEN
    ALTER TABLE report_templates
        ADD COLUMN IF NOT EXISTS retest_template_file_id VARCHAR(255),
        ADD COLUMN IF NOT EXISTS retest_template_file_name VARCHAR(255),
        ADD COLUMN IF NOT EXISTS retest_template_file_size BIGINT,
        ADD COLUMN IF NOT EXISTS retest_template_file_content_type VARCHAR(255);
  END IF;
END $$;
