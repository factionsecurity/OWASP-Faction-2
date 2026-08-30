DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'users') THEN
    ALTER TABLE users
        ADD COLUMN IF NOT EXISTS profile_image_id VARCHAR(255),
        ADD COLUMN IF NOT EXISTS profile_image_key VARCHAR(1024);
  END IF;
END $$;
