-- What this installation calls each severity.
--
-- Labels only. The VulnerabilitySeverity enum stays CRITICAL..INFORMATIONAL, so stored findings,
-- the vulnerability_events hypertable, the SLA config and both export formats are untouched by a
-- rename. Defaults match the entity's @Builder.Default, so an install that never opens the setting
-- reads exactly as it did before.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'terminology_config') THEN
    ALTER TABLE terminology_config
        ADD COLUMN IF NOT EXISTS severity_critical      VARCHAR(255) NOT NULL DEFAULT 'Critical',
        ADD COLUMN IF NOT EXISTS severity_high          VARCHAR(255) NOT NULL DEFAULT 'High',
        ADD COLUMN IF NOT EXISTS severity_medium        VARCHAR(255) NOT NULL DEFAULT 'Medium',
        ADD COLUMN IF NOT EXISTS severity_low           VARCHAR(255) NOT NULL DEFAULT 'Low',
        ADD COLUMN IF NOT EXISTS severity_informational VARCHAR(255) NOT NULL DEFAULT 'Informational';
  END IF;
END $$;
