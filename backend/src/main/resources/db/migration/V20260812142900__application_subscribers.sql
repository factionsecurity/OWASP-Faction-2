-- Explicit thread membership on an application, mirroring vulnerabilities.subscribers.
--
-- Everyone in this list is notified in app and by email when a comment is added, and can
-- reply to that email to post back. Explicit rather than derived from who has commented, so
-- membership is visible and can be left.
--
-- Hibernate owns the table, so the ALTER is guarded: Flyway runs before Hibernate's schema
-- bootstrap and the table does not exist yet on a fresh database, where the entity
-- definition creates the column correctly anyway.

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'applications') THEN
    ALTER TABLE applications
        ADD COLUMN IF NOT EXISTS subscribers JSONB DEFAULT '[]'::jsonb;
  END IF;
END $$;
