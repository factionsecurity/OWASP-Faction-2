-- Retest evidence (per retest, so a vulnerability keeps a history across rounds) and the
-- bookkeeping for retest reports: when an assessment's retest report was last generated, when a
-- retest's evidence was locked into one, and whether a workflow lets locked evidence be edited.
--
-- Guarded for fresh installs, where these Hibernate-owned tables don't exist yet at migration
-- time (Flyway runs before Hibernate's schema bootstrap); there the entity definitions create
-- the columns.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'retests') THEN
    ALTER TABLE retests
        ADD COLUMN IF NOT EXISTS evidence TEXT,
        ADD COLUMN IF NOT EXISTS evidence_updated_at TIMESTAMP,
        ADD COLUMN IF NOT EXISTS evidence_locked_at TIMESTAMP;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments
        ADD COLUMN IF NOT EXISTS retest_report_generated_at TIMESTAMP;
  END IF;
  -- Backfill the stamp on upgrade. A null stamp means "never reported", so without this every
  -- historical assessment with a past PASSED/FAILED retest would show as ready for a retest report
  -- the moment this ships. Existing work counts as already reported through its latest completed
  -- retest; only retests completed after the upgrade prompt a report. Assessments that already
  -- carry a stamp are left alone, so re-running this is a no-op.
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments')
     AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'retests') THEN
    UPDATE assessments a
       SET retest_report_generated_at = (
             SELECT MAX(r.closed_date) FROM retests r
              WHERE r.assessment_id = a.id
                AND r.status IN ('PASSED', 'FAILED')
                AND r.deleted_at IS NULL)
     WHERE a.retest_report_generated_at IS NULL
       AND EXISTS (
             SELECT 1 FROM retests r
              WHERE r.assessment_id = a.id
                AND r.status IN ('PASSED', 'FAILED')
                AND r.deleted_at IS NULL
                AND r.closed_date IS NOT NULL);
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessment_workflows') THEN
    ALTER TABLE assessment_workflows
        ADD COLUMN IF NOT EXISTS allow_retest_evidence_edit_after_report BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
  -- report_documents.doc_type gains RETEST_DOCX / RETEST_PDF / RETEST_ENCRYPTED_PDF. V7 created it
  -- as a plain VARCHAR(32); drop a Hibernate-generated enum check if one exists anywhere.
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'report_documents') THEN
    ALTER TABLE report_documents DROP CONSTRAINT IF EXISTS report_documents_doc_type_check;
  END IF;
END $$;
