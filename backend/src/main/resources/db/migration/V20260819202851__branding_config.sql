-- White-label branding: which images replace the shipped Faction marks.
-- Only ids, keys and content types live here; the bytes are in object storage.

CREATE TABLE IF NOT EXISTS branding_config (
    id                            VARCHAR(255) PRIMARY KEY,
    login_logo_id                 VARCHAR(255),
    login_logo_key                VARCHAR(512),
    login_logo_content_type       VARCHAR(128),
    menu_logo_large_id            VARCHAR(255),
    menu_logo_large_key           VARCHAR(512),
    menu_logo_large_content_type  VARCHAR(128),
    menu_logo_small_id            VARCHAR(255),
    menu_logo_small_key           VARCHAR(512),
    menu_logo_small_content_type  VARCHAR(128),
    favicon_id                    VARCHAR(255),
    favicon_key                   VARCHAR(512),
    favicon_content_type          VARCHAR(128),
    login_backgrounds             JSONB NOT NULL DEFAULT '[]'::jsonb
);
