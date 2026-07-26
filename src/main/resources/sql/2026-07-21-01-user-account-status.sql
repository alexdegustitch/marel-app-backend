-- =============================================================================
-- User account status
-- =============================================================================
-- Until now a user account had exactly one workflow signal: users.is_active.
-- Registration (AuthService.register / completeGoogleRegistration) created the
-- row with is_active = false meaning "waiting for an administrator", while an
-- admin deactivating an existing account produced the *same* false value. Those
-- are different business states and the system could not tell them apart, and
-- there was no way at all to express "an administrator refused this account".
--
-- account_status becomes the authoritative workflow state. is_active is kept
-- (existing queries, specifications, DTOs and the frontend all read it) but is
-- now DERIVED from account_status by a trigger, so the two can never contradict
-- each other. There is deliberately no second boolean: no is_approved, no
-- is_declined.
--
-- Pre-existing archive behaviour is intentionally left untouched. The existing
-- trg_01_users_clear_archive_on_reactivate / trg_02_users_archived_at triggers
-- continue to manage archived_at off is_active transitions exactly as before, so
-- deactivating an account still stamps archived_at. account_status is the
-- workflow state; archived_at remains the pre-existing "not currently usable"
-- marker. See docs/business-rules/user-requests-mailing-notifications-and-preferences.md.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS activated_at   TIMESTAMPTZ;

-- Backfill from the only signals that existed before this migration.
--   archived_at set                    -> the account was archived
--   is_active = false, never archived  -> registered, still waiting for approval
--   is_active = true                   -> a normal working account
-- A previously *declined* account cannot be recovered from the old data because
-- the concept did not exist; such rows land in PENDING_APPROVAL, which is the
-- safe direction (still no access, still reviewable).
UPDATE users
SET account_status = CASE
        WHEN archived_at IS NOT NULL THEN 'ARCHIVED'
        WHEN is_active = FALSE       THEN 'PENDING_APPROVAL'
        ELSE 'ACTIVE'
    END
WHERE account_status IS NULL;

-- Accounts that are already usable have been active since (at least) creation.
UPDATE users
SET activated_at = created_at
WHERE activated_at IS NULL
  AND account_status = 'ACTIVE';

ALTER TABLE users
    ALTER COLUMN account_status SET NOT NULL,
    ALTER COLUMN account_status SET DEFAULT 'PENDING_APPROVAL';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_account_status'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT chk_users_account_status
            CHECK (account_status IN (
                'PENDING_APPROVAL', 'ACTIVE', 'DECLINED', 'SUSPENDED', 'ARCHIVED'
            ));
    END IF;
END $$;

-- is_active must always agree with account_status. The sync is bidirectional so
-- that pre-existing code paths which only touch is_active (UserService.update,
-- the admin activate/deactivate toggle) keep working and land on a sensible
-- status instead of silently desynchronising.
CREATE OR REPLACE FUNCTION public.sync_user_account_status()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- account_status is authoritative on insert; is_active is derived.
        NEW.is_active := (NEW.account_status = 'ACTIVE');

        IF NEW.account_status = 'ACTIVE' AND NEW.activated_at IS NULL THEN
            NEW.activated_at := now();
        END IF;

        RETURN NEW;
    END IF;

    IF NEW.account_status IS DISTINCT FROM OLD.account_status THEN
        -- Explicit workflow transition wins over any is_active value supplied
        -- in the same statement.
        NEW.is_active := (NEW.account_status = 'ACTIVE');

        IF NEW.account_status = 'ACTIVE' AND NEW.activated_at IS NULL THEN
            NEW.activated_at := now();
        END IF;

    ELSIF NEW.is_active IS DISTINCT FROM OLD.is_active THEN
        -- Legacy path: only the boolean was touched. Reactivating means ACTIVE;
        -- deactivating a working account means SUSPENDED (an administrative act),
        -- never DECLINED — declining is a registration-review outcome and must go
        -- through the registration workflow.
        IF NEW.is_active THEN
            NEW.account_status := 'ACTIVE';
            IF NEW.activated_at IS NULL THEN
                NEW.activated_at := now();
            END IF;
        ELSIF OLD.account_status = 'ACTIVE' THEN
            NEW.account_status := 'SUSPENDED';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

-- trg_00_* so the derivation happens before the pre-existing archived_at
-- triggers (trg_01/trg_02) observe the is_active transition.
DROP TRIGGER IF EXISTS trg_00_users_account_status_sync ON users;
CREATE TRIGGER trg_00_users_account_status_sync
    BEFORE INSERT OR UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION public.sync_user_account_status();

-- Supports the admin "accounts waiting for approval" screen and any
-- status-filtered user listing.
CREATE INDEX IF NOT EXISTS idx_users_account_status
    ON users (account_status);

COMMENT ON COLUMN users.account_status IS
    'Authoritative account workflow state. is_active is derived from it by trg_00_users_account_status_sync.';
COMMENT ON COLUMN users.activated_at IS
    'First time the account reached ACTIVE. Never cleared once set.';
