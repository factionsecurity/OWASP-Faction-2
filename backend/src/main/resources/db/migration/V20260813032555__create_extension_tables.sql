-- App Store extensions.
--
-- New tables, so no DO $$ IF EXISTS(table) guard is needed here — that guard is
-- for ALTERs against Hibernate-owned tables that may not exist yet on a fresh
-- database. CREATE TABLE IF NOT EXISTS is safe to run before or after
-- Hibernate's bootstrap, and keeps the migration idempotent.

CREATE TABLE IF NOT EXISTS extension (
    id                      VARCHAR(255) PRIMARY KEY,
    name                    VARCHAR(255),
    author                  VARCHAR(255),
    version                 VARCHAR(255),
    url                     VARCHAR(255),
    description             TEXT,
    logo_base64             TEXT,
    logo_mime_type          VARCHAR(255),
    jar_file_id             VARCHAR(255),
    hash                    VARCHAR(255),
    encrypted_configs       TEXT,

    enabled                 BOOLEAN NOT NULL DEFAULT FALSE,
    display_order           INTEGER NOT NULL DEFAULT 0,

    assessment_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    assessment_order        INTEGER NOT NULL DEFAULT 0,
    vulnerability_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    vulnerability_order     INTEGER NOT NULL DEFAULT 0,
    verification_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    verification_order      INTEGER NOT NULL DEFAULT 0,
    inventory_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_order         INTEGER NOT NULL DEFAULT 0,
    report_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    report_order            INTEGER NOT NULL DEFAULT 0,

    provides_assessment     BOOLEAN NOT NULL DEFAULT FALSE,
    provides_vulnerability  BOOLEAN NOT NULL DEFAULT FALSE,
    provides_verification   BOOLEAN NOT NULL DEFAULT FALSE,
    provides_inventory      BOOLEAN NOT NULL DEFAULT FALSE,
    provides_report         BOOLEAN NOT NULL DEFAULT FALSE,

    created_by              VARCHAR(255),
    last_updated_by         VARCHAR(255),
    created_at              TIMESTAMP,
    updated_at              TIMESTAMP,
    deleted_at              TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_extension_enabled ON extension (enabled);
CREATE INDEX IF NOT EXISTS idx_extension_deleted ON extension (deleted_at);

CREATE TABLE IF NOT EXISTS extension_log (
    id            VARCHAR(255) PRIMARY KEY,
    extension_id  VARCHAR(255),
    level         VARCHAR(32),
    event_type    VARCHAR(64),
    message       TEXT,
    stack_trace   TEXT,
    timestamp     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_extension_log_extension
    ON extension_log (extension_id, timestamp);
