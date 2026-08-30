-- Peer review self-review toggle. ddl-auto=update does not reliably add this
-- column to existing instances, so add it explicitly.
-- Guarded for fresh installs, where the assessment_workflow_config table doesn't
-- exist yet at migration time (Flyway runs before Hibernate's schema bootstrap) —
-- there, the entity's @Builder.Default gives the right default when the table is
-- first created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessment_workflow_config') THEN
    ALTER TABLE assessment_workflow_config
        ADD COLUMN IF NOT EXISTS allow_self_peer_review BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
END $$;
