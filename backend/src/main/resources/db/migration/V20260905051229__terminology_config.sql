-- What this installation calls organizations and sub-organizations. One row.
--
-- Defaults match the entity's @Builder.Default and the product's existing wording, so an install
-- that never opens the setting reads exactly as it did before.
CREATE TABLE IF NOT EXISTS terminology_config (
    id                          VARCHAR(255) PRIMARY KEY,
    organization_singular       VARCHAR(255) NOT NULL DEFAULT 'Organization',
    organization_plural         VARCHAR(255) NOT NULL DEFAULT 'Organizations',
    sub_organization_singular   VARCHAR(255) NOT NULL DEFAULT 'Sub-organization',
    sub_organization_plural     VARCHAR(255) NOT NULL DEFAULT 'Sub-organizations'
);
