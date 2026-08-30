-- Sub-organizations: divisions within an organization that applications can be attributed to.
-- The owning organization is unchanged — applications keep organization_id and gain an optional
-- sub_organization_id — so no existing access check or org-scoped query is affected.
CREATE TABLE IF NOT EXISTS sub_organizations (
    id               VARCHAR(255) PRIMARY KEY,
    organization_id  VARCHAR(255) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    description      VARCHAR(255),
    created_by       VARCHAR(255),
    last_updated_by  VARCHAR(255),
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sub_organizations_organizationid
    ON sub_organizations (organization_id);

-- Unique per organization, not globally: two organizations may each have an "EMEA".
CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_organizations_org_name
    ON sub_organizations (organization_id, name);

-- Guarded because Flyway runs before Hibernate's schema bootstrap: on a fresh database the
-- applications table does not exist yet, and the entity definition adds the column anyway.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'applications') THEN
    ALTER TABLE applications
        ADD COLUMN IF NOT EXISTS sub_organization_id VARCHAR(255);
    CREATE INDEX IF NOT EXISTS idx_applications_suborganizationid
        ON applications (sub_organization_id);
  END IF;
END $$;
