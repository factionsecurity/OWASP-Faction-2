-- Campaigns: lightweight named tags optionally assigned to assessments,
-- used as a filter dimension (Manager Dashboard, assessment search).

CREATE TABLE IF NOT EXISTS campaigns (
    id         VARCHAR(255) PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6),
    CONSTRAINT uq_campaigns_name UNIQUE (name)
);

-- Nullable campaign reference on assessments. Guarded: Flyway runs before
-- Hibernate's schema bootstrap, so on a fresh database the assessments table
-- does not exist yet and the entity definition creates the column instead.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'assessments') THEN
    ALTER TABLE assessments
        ADD COLUMN IF NOT EXISTS campaign_id VARCHAR(255);
    CREATE INDEX IF NOT EXISTS idx_assessments_campaignid ON assessments (campaign_id);
  END IF;
END $$;
