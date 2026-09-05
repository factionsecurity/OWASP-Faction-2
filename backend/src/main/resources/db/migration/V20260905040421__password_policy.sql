-- Password and sign-in rules, set by an administrator rather than baked into the build or read
-- from an environment variable. One row.
--
-- Defaults deliberately mirror the entity's @Builder.Default so a row created by Hibernate and a
-- row created here are the same row. lockout_duration_minutes defaults to a cooldown rather than
-- a permanent lock: with no rate limit in front of the login endpoint, a permanent lock lets any
-- anonymous caller who knows a username hold that account shut until an admin intervenes.
CREATE TABLE IF NOT EXISTS password_policy (
    id                        VARCHAR(255) PRIMARY KEY,
    max_failed_login_attempts INTEGER NOT NULL DEFAULT 5,
    lockout_duration_minutes  INTEGER NOT NULL DEFAULT 15,
    minimum_length            INTEGER NOT NULL DEFAULT 12,
    require_uppercase         BOOLEAN NOT NULL DEFAULT TRUE,
    require_lowercase         BOOLEAN NOT NULL DEFAULT TRUE,
    require_digit             BOOLEAN NOT NULL DEFAULT TRUE,
    require_symbol            BOOLEAN NOT NULL DEFAULT FALSE
);

-- A lockout that expires on its own needs its own timestamp. disabled_at cannot carry it: an
-- administrator disabling somebody and the system locking them out briefly are different things,
-- and a cooldown must never quietly re-enable an account a human switched off.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'users') THEN
    ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
  END IF;
END $$;
