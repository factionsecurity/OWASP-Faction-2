-- Notebook note content is HTML from the RichTextEditor, and pasted tool output (nmap, dig, ...)
-- runs far past 255 characters. Hibernate created content and content_text as varchar(255)
-- since the entity had no @Column annotation, so saving a longer note failed with a 500.
-- ddl-auto=update does not alter an existing column's type on its own, so this must be done
-- explicitly. Guarded for fresh installs, where the notebook_nodes table doesn't exist yet at
-- migration time (Flyway runs before Hibernate's schema bootstrap); there, the entity's
-- @Column(columnDefinition = "TEXT") already gives the right type when the table is created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'notebook_nodes') THEN
    ALTER TABLE notebook_nodes ALTER COLUMN content TYPE text;
    ALTER TABLE notebook_nodes ALTER COLUMN content_text TYPE text;
  END IF;
END $$;
