-- Per-document tracking for generated report artifacts (DOCX / PDF / encrypted PDF).
-- ddl-auto=update does not reliably create/alter existing tables, so do it explicitly.
CREATE TABLE IF NOT EXISTS report_documents (
    id            VARCHAR(255) PRIMARY KEY,
    assessment_id VARCHAR(255) NOT NULL,
    doc_type      VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    file_id       VARCHAR(255),
    generated_at  TIMESTAMP,
    error_message VARCHAR(2000),
    updated_at    TIMESTAMP,
    CONSTRAINT uq_report_documents_assessment_type UNIQUE (assessment_id, doc_type)
);

CREATE INDEX IF NOT EXISTS idx_report_documents_assessment
    ON report_documents (assessment_id);

-- Per-assessment password for the encrypted PDF variant, stored AES-GCM
-- encrypted with the SSO_ENCRYPTION_KEY (see EncryptionService).
-- Guarded for fresh installs, where the assessments table doesn't exist yet at
-- migration time (Flyway runs before Hibernate's schema bootstrap) — there,
-- Hibernate creates the column from the entity when the table is first created.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments
        ADD COLUMN IF NOT EXISTS report_password_encrypted VARCHAR(512);
  END IF;
END $$;
