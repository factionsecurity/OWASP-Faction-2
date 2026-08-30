-- Phase 6: per-user, per-category notification opt-out.
--
-- No backfill. A missing row means enabled, so every existing user keeps exactly the
-- behaviour they have today and rows appear only when someone changes something. That
-- also means adding a category later needs no data migration.

CREATE TABLE IF NOT EXISTS notification_preference (
    id             VARCHAR(255) PRIMARY KEY,
    username       VARCHAR(255) NOT NULL,
    category       VARCHAR(48)  NOT NULL,
    in_app_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    email_enabled  BOOLEAN      NOT NULL DEFAULT TRUE
);

-- One row per user per category; the service upserts against this.
CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_preference_user_category
    ON notification_preference (username, category);
