-- Toggle allowing freeform "Ask AI" editor queries to use web search/fetch.
-- ddl-auto=update does not reliably add columns to existing instances, so add
-- it explicitly. Guarded for fresh installs, where the web_search_config table
-- doesn't exist yet at migration time (Flyway runs before Hibernate's schema
-- bootstrap) — there, the entity definition creates the column with the table.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'web_search_config') THEN
    ALTER TABLE web_search_config
        ADD COLUMN IF NOT EXISTS allow_in_ask_ai BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
END $$;
