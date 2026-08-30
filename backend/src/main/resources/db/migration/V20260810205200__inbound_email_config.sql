-- Phase 3 of the email integration: IMAP settings for collecting @mention replies.
--
-- A singleton row, like email_config / sso_config / ai_log_config, so an admin can edit
-- it at runtime instead of needing an env var and a restart. The reply address lives here
-- rather than on email_config because outbound SMTP and inbound IMAP are independently
-- useful — notification email without reply-by-email is a normal deployment.
--
-- CREATE TABLE rather than an ALTER, so no IF EXISTS(table) guard is needed; Hibernate
-- will find the table already present when it bootstraps.

CREATE TABLE IF NOT EXISTS inbound_email_config (
    id                    VARCHAR(255) PRIMARY KEY,
    enabled               BOOLEAN      NOT NULL DEFAULT FALSE,
    reply_address         VARCHAR(255),
    provider              VARCHAR(64)  DEFAULT 'CUSTOM',
    host                  VARCHAR(255),
    port                  INTEGER      DEFAULT 993,
    username              VARCHAR(255),
    encrypted_password    VARCHAR(2048),
    security              VARCHAR(32)  DEFAULT 'SSL_TLS',
    folder                VARCHAR(255) DEFAULT 'INBOX',
    processed_folder      VARCHAR(255),
    poll_interval_seconds INTEGER      DEFAULT 60,
    max_message_bytes     INTEGER      DEFAULT 1048576,
    last_polled_at        TIMESTAMP,
    last_poll_error       VARCHAR(1024)
);

-- Seed the singleton so the admin page has a row to read on first load. Defaults leave
-- reply-by-email switched off: a blank reply address means mention emails omit the reply
-- invitation rather than inviting a reply that has nowhere to land.
INSERT INTO inbound_email_config (id, enabled, provider, port, security, folder,
                                  poll_interval_seconds, max_message_bytes)
VALUES ('singleton', FALSE, 'CUSTOM', 993, 'SSL_TLS', 'INBOX', 60, 1048576)
ON CONFLICT (id) DO NOTHING;
