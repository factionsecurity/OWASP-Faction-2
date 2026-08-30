-- Report font for generated DOCX rich-text content (feeds DocxUtils.FONT),
-- configured per report template with a snapshot on each assessment.
-- Guarded for fresh installs, where these Hibernate-owned tables don't exist
-- yet at migration time (Flyway runs before Hibernate's schema bootstrap) —
-- there, the entity definitions create the columns when the tables are created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'report_templates') THEN
    ALTER TABLE report_templates
        ADD COLUMN IF NOT EXISTS font VARCHAR(255);
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments
        ADD COLUMN IF NOT EXISTS template_font VARCHAR(255);
  END IF;
END $$;
