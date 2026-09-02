-- Reusable rich text boilerplate inserted into assessment and vulnerability editors.
-- Created explicitly rather than left to ddl-auto so a running instance picks the table up
-- on deploy; IF NOT EXISTS keeps it idempotent where Hibernate got there first.
CREATE TABLE IF NOT EXISTS content_template (
    id                VARCHAR(255) PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    description       VARCHAR(255),
    scope             VARCHAR(255) NOT NULL,
    content           TEXT         NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by        VARCHAR(255),
    last_updated_by   VARCHAR(255),
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP
);

-- The picker queries one scope at a time, enabled only.
CREATE INDEX IF NOT EXISTS idx_content_template_scope_enabled
    ON content_template (scope, enabled);
