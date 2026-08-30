-- Configurable rendered heights for the sign-in and menu logos.
-- NULL means "use the shipped default for that slot", so existing installs are unchanged.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'branding_config') THEN
    ALTER TABLE branding_config
        ADD COLUMN IF NOT EXISTS login_logo_height       INTEGER,
        ADD COLUMN IF NOT EXISTS menu_logo_large_height  INTEGER,
        ADD COLUMN IF NOT EXISTS menu_logo_small_height  INTEGER;
  END IF;
END $$;
