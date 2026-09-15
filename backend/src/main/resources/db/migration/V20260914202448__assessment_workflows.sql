-- Assessment workflows (phase 1): the single workflow configuration becomes Default Workflow.
--
-- On an existing installation this creates assessment_workflows (columns matching Hibernate's DDL for
-- AssessmentWorkflow), copies every value of the 'singleton' assessment_workflow_config row into the
-- 'default' row with remediation stage ids unchanged, and puts every assessment type and assessment on
-- it through a constant column default (metadata-only in Postgres: no table rewrite).
--
-- Guarded: Flyway runs before Hibernate creates the tables on a fresh database, where the entities
-- create all of this and BootstrapService seeds Default Workflow. The guards filter on
-- current_schema() so the migration test can run this file in a scratch schema. Idempotent: the table,
-- index and columns use IF NOT EXISTS and the row ON CONFLICT DO NOTHING, so a re-run never overwrites
-- edits made since. With no singleton row (the configuration was never read) no row is inserted;
-- bootstrap creates Default Workflow with the defaults at startup, as getConfig() used to on first read.
-- assessment_workflow_config is left in place, unread, for one release as a rollback path.
--
-- This creates a unique INDEX idx_assessment_workflows_name, whereas Hibernate creates a unique
-- CONSTRAINT of the same name on fresh installs (see AssessmentWorkflow's @Table indexes). A later
-- change that drops it must cover both: DROP INDEX IF EXISTS ... and DROP CONSTRAINT IF EXISTS ...
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_name = 'assessments' AND table_schema = current_schema()) THEN
    CREATE TABLE IF NOT EXISTS assessment_workflows (
        id                     VARCHAR(255) NOT NULL PRIMARY KEY,
        name                   VARCHAR(255) NOT NULL,
        is_default             BOOLEAN      NOT NULL DEFAULT FALSE,
        archived               BOOLEAN      NOT NULL DEFAULT FALSE,
        statuses               JSONB,
        new_assessment_status  VARCHAR(255),
        in_progress_status     VARCHAR(255),
        completed_status       VARCHAR(255),
        status_colors          JSONB,
        vulnerability_slas     JSONB,
        vulnerability_statuses JSONB,
        remediation_stages     JSONB,
        allow_self_peer_review BOOLEAN      NOT NULL DEFAULT FALSE,
        created_at             TIMESTAMP(6),
        updated_at             TIMESTAMP(6)
    );
    CREATE UNIQUE INDEX IF NOT EXISTS idx_assessment_workflows_name ON assessment_workflows (name);

    IF EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_name = 'assessment_workflow_config' AND table_schema = current_schema()) THEN
      INSERT INTO assessment_workflows (id, name, is_default, archived, statuses, new_assessment_status,
                                        in_progress_status, completed_status, status_colors,
                                        vulnerability_slas, vulnerability_statuses, remediation_stages,
                                        allow_self_peer_review, created_at, updated_at)
      SELECT 'default', 'Default Workflow', TRUE, FALSE,
             c.statuses::jsonb, c.new_assessment_status, c.in_progress_status, c.completed_status,
             c.status_colors::jsonb, c.vulnerability_slas::jsonb, c.vulnerability_statuses::jsonb,
             c.remediation_stages::jsonb, COALESCE(c.allow_self_peer_review, FALSE),
             timezone('UTC', now())::timestamp(6), timezone('UTC', now())::timestamp(6)
        FROM assessment_workflow_config c
       WHERE c.id = 'singleton'
      ON CONFLICT (id) DO NOTHING;
    END IF;
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_name = 'assessment_types' AND table_schema = current_schema()) THEN
    ALTER TABLE assessment_types ADD COLUMN IF NOT EXISTS workflow_id VARCHAR(255) NOT NULL DEFAULT 'default';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_name = 'assessments' AND table_schema = current_schema()) THEN
    ALTER TABLE assessments ADD COLUMN IF NOT EXISTS workflow_id VARCHAR(255) NOT NULL DEFAULT 'default';
  END IF;
END $$;
