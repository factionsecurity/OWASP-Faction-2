-- Admin routing table for outbound notification email, plus the per-finding bookkeeping
-- the SLA digest job uses to decide who has already been told what.

CREATE TABLE IF NOT EXISTS email_notification_config (
    id                            VARCHAR(255) PRIMARY KEY,
    enabled                       BOOLEAN NOT NULL DEFAULT FALSE,
    events                        JSONB   NOT NULL DEFAULT '{}'::jsonb,
    past_due_repeat_count         INTEGER NOT NULL DEFAULT 0,
    past_due_repeat_interval_days INTEGER NOT NULL DEFAULT 7
);

-- Hibernate owns the vulnerabilities table, and Flyway runs before its schema bootstrap:
-- on a fresh database the table does not exist yet and an unguarded ALTER fails startup.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'vulnerabilities') THEN
    ALTER TABLE vulnerabilities
        ADD COLUMN IF NOT EXISTS warning_notified_at      TIMESTAMP,
        ADD COLUMN IF NOT EXISTS past_due_notified_at     TIMESTAMP,
        ADD COLUMN IF NOT EXISTS past_due_reminder_count  INTEGER DEFAULT 0;
  END IF;
END $$;
