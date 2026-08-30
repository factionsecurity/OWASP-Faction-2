-- Phase 4: audit trail for inbound @mention replies.
--
-- Every message the poller considers gets a row, accepted or not. An ingest path that
-- silently drops mail is undiagnosable — the same trap the outbound side fell into by
-- logging failures at debug — and the rejected rows double as a security audit.

CREATE TABLE IF NOT EXISTS inbound_email_log (
    id                 VARCHAR(255) PRIMARY KEY,
    received_at        TIMESTAMP,
    message_id         VARCHAR(998),
    from_address       VARCHAR(255),
    subject            VARCHAR(998),
    token_id           VARCHAR(64),
    status             VARCHAR(16),
    reason             VARCHAR(1024),
    target_type        VARCHAR(32),
    target_id          VARCHAR(255),
    created_comment_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_inbound_email_log_received_at
    ON inbound_email_log (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_inbound_email_log_message_id
    ON inbound_email_log (message_id);
