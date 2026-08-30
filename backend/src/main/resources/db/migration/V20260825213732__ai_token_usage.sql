-- Daily AI token ledger. Kept separate from ai_request_log because that table is opt-in
-- and purged on a retention window, which makes it unusable as budget history.
CREATE TABLE IF NOT EXISTS ai_token_usage_daily (
    id            VARCHAR(255) PRIMARY KEY,
    usage_date    DATE         NOT NULL,
    username      VARCHAR(255) NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    model         VARCHAR(255) NOT NULL,
    input_tokens  BIGINT       NOT NULL DEFAULT 0,
    output_tokens BIGINT       NOT NULL DEFAULT 0,
    request_count BIGINT       NOT NULL DEFAULT 0
);

-- The upsert in AiTokenUsageDayRepository conflict-targets exactly these columns.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_token_usage_day
    ON ai_token_usage_daily (usage_date, username, provider_name, model);

CREATE INDEX IF NOT EXISTS idx_ai_token_usage_date
    ON ai_token_usage_daily (usage_date);

-- Per-request token counts on the audit log, so a spike in the chart can be traced to the
-- calls that caused it. Guarded: Flyway runs before Hibernate creates the table on a fresh DB.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ai_request_log') THEN
    ALTER TABLE ai_request_log
        ADD COLUMN IF NOT EXISTS input_tokens  INTEGER NOT NULL DEFAULT 0,
        ADD COLUMN IF NOT EXISTS output_tokens INTEGER NOT NULL DEFAULT 0;
  END IF;
END $$;
