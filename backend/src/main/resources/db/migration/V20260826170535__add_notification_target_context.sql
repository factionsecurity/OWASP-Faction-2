-- Notification rows now carry what they are about, so the mentions dashboard can group
-- by item type and quote the comment without parsing links or loading other entities.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'notifications') THEN
    ALTER TABLE notifications
        ADD COLUMN IF NOT EXISTS target_type VARCHAR(32),
        ADD COLUMN IF NOT EXISTS target_id VARCHAR(255),
        ADD COLUMN IF NOT EXISTS target_name VARCHAR(255),
        ADD COLUMN IF NOT EXISTS actor_username VARCHAR(255),
        ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255),
        ADD COLUMN IF NOT EXISTS excerpt TEXT;

    -- Backfill the kind of item for rows recorded before the column existed. Only the
    -- deep link survives on those, and its shape is fixed per target, so the grouping
    -- can be recovered even though the name and excerpt cannot.
    UPDATE notifications
       SET target_type = 'APPLICATION'
     WHERE target_type IS NULL AND link LIKE '/applications/%';

    UPDATE notifications
       SET target_type = 'VULNERABILITY'
     WHERE target_type IS NULL AND link LIKE '/vulnerabilities%';

    UPDATE notifications
       SET target_type = 'NOTEBOOK'
     WHERE target_type IS NULL AND link LIKE '%notebook%';

    -- The mentions feed reads one user's rows filtered by type, newest first.
    CREATE INDEX IF NOT EXISTS idx_notifications_username_type
        ON notifications (username, type);
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'mention_queue') THEN
    ALTER TABLE mention_queue
        ADD COLUMN IF NOT EXISTS target_name VARCHAR(255);
  END IF;
END $$;
