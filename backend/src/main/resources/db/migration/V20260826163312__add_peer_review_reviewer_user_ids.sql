-- A peer review can be worked by several reviewers at once, so it records the full set rather
-- than only whoever claimed it first. Existing rows are seeded from reviewed_by_user_id so their
-- reviewer keeps showing.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'peer_reviews') THEN
    ALTER TABLE peer_reviews
        ADD COLUMN IF NOT EXISTS reviewer_user_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

    UPDATE peer_reviews
       SET reviewer_user_ids = jsonb_build_array(reviewed_by_user_id)
     WHERE reviewed_by_user_id IS NOT NULL
       AND reviewer_user_ids = '[]'::jsonb;
  END IF;
END $$;
