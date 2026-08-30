-- Per-prompt toggle allowing the AI to search the web and fetch URLs.
-- ddl-auto=update does not reliably add columns to existing instances, so add
-- it explicitly. Guarded for fresh installs, where the ai_prompt_template table
-- doesn't exist yet at migration time (Flyway runs before Hibernate's schema
-- bootstrap) — there, the entity definition creates the column with the table.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ai_prompt_template') THEN
    ALTER TABLE ai_prompt_template
        ADD COLUMN IF NOT EXISTS allow_web_access BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
END $$;
