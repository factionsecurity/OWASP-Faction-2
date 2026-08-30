-- The application description is edited via the frontend's RichTextEditor and can
-- hold HTML far longer than 255 characters. Hibernate created it as varchar(255)
-- since the entity previously had no @Column annotation, and ddl-auto=update does
-- not alter an existing column's type on its own, so this must be done explicitly.
-- Guarded for fresh installs, where the applications table doesn't exist yet at
-- migration time (Flyway runs before Hibernate's schema bootstrap) — there, the
-- entity's @Column(columnDefinition = "TEXT") already gives the right type when
-- the table is first created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'applications') THEN
    ALTER TABLE applications ALTER COLUMN description TYPE text;
  END IF;
END $$;
