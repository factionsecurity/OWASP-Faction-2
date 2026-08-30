-- Retest scope and comment are edited via the frontend's RichTextEditor and hold HTML
-- far longer than 255 characters. Hibernate created them as varchar(255) since the entity
-- previously had no @Column annotation, and ddl-auto=update does not alter an existing
-- column's type on its own, so this must be done explicitly.
-- Guarded for fresh installs, where the retests table doesn't exist yet at migration time
-- (Flyway runs before Hibernate's schema bootstrap) — there, the entity's
-- @Column(columnDefinition = "TEXT") already gives the right type when the table is created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'retests') THEN
    ALTER TABLE retests ALTER COLUMN scope TYPE text;
    ALTER TABLE retests ALTER COLUMN "comment" TYPE text;
  END IF;
END $$;
