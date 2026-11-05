-- Whether notification emails name applications and vulnerabilities. On by default, matching
-- what those emails did before the switches existed.

ALTER TABLE email_notification_config
    ADD COLUMN IF NOT EXISTS include_application_names   BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS include_vulnerability_names BOOLEAN NOT NULL DEFAULT TRUE;
