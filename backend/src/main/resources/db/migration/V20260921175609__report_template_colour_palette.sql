-- The colour palette a report template renders its findings in: what each painted colour
-- sentinel (FAC701/1A0701 and friends) resolves to for severity, likelihood, impact and any
-- user-defined field given colours.
--
-- Replaces the colour/cells/fill/custom-fields marker paragraphs that used to carry this inside
-- the .docx. Those were keyed on the *displayed* severity label, so renaming a severity in the
-- terminology settings silently returned every finding to black.
--
-- (Those markers are written with a dollar-brace prefix in the template. Not spelled out here:
-- Flyway substitutes that syntax as a placeholder and fails the migration when it cannot resolve
-- one, which is a confusing way to take down every repository test.)
--
-- Configured per report template, with a snapshot on each assessment for the same reason the CSS
-- is snapshotted: a template can be deleted while assessments still reference it.
--
-- Guarded for fresh installs, where these Hibernate-owned tables don't exist yet at migration
-- time (Flyway runs before Hibernate's schema bootstrap) — there, the entity definitions create
-- the columns when the tables are created.
--
-- No default: a null palette resolves every sentinel to black on white, which is what a template
-- predating this feature should do until someone opens the colour pickers and saves it.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'report_templates') THEN
    ALTER TABLE report_templates
        ADD COLUMN IF NOT EXISTS report_palette JSONB;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments
        ADD COLUMN IF NOT EXISTS template_palette JSONB;
  END IF;
END $$;
