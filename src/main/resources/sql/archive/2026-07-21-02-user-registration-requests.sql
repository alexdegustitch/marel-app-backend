-- =============================================================================
-- User registration requests
-- =============================================================================
-- Self-registration already created the user row (inactive) but left no record
-- of the approval decision itself: who reviewed it, when, or why it was refused.
-- This table is that record. It is never physically deleted after review.
--
-- Relationship to users.account_status (2026-07-21-user-account-status.sql):
--   request PENDING   <-> user PENDING_APPROVAL
--   request APPROVED  <-> user ACTIVE
--   request DECLINED  <-> user DECLINED
-- Both sides are written in a single transaction by
-- UserRegistrationRequestService, never independently.
--
-- Email verification is NOT approval. A verified Google identity still lands in
-- PENDING and still needs an administrator.
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_registration_requests (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    review_note  VARCHAR(1000),
    reviewed_by  BIGINT,
    reviewed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    -- Optimistic locking: two administrators reviewing the same pending request
    -- concurrently must not both succeed. The loser gets an
    -- ObjectOptimisticLockingFailureException, surfaced as HTTP 409.
    version      BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_user_registration_requests_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_registration_requests_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT chk_user_registration_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED')),

    -- Reviewer fields and status can never disagree:
    --   PENDING            -> not yet reviewed, both reviewer fields empty
    --   APPROVED/DECLINED  -> a human decided, both reviewer fields required
    --   CANCELLED          -> withdrawn, timestamped; reviewed_by is NULL when
    --                        the system cancelled it rather than a person
    CONSTRAINT chk_user_registration_requests_review_state
        CHECK (
            (status = 'PENDING'
                AND reviewed_at IS NULL AND reviewed_by IS NULL)
            OR (status IN ('APPROVED', 'DECLINED')
                AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)
            OR (status = 'CANCELLED'
                AND reviewed_at IS NOT NULL)
        ),

    CONSTRAINT chk_user_registration_requests_review_note
        CHECK (review_note IS NULL OR length(trim(review_note)) > 0)
);

-- A user may accumulate a history of requests, but only ever one open one.
-- Enforced in the database so a double-submitted registration cannot slip
-- through between the application's check and its insert.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_registration_requests_one_pending
    ON user_registration_requests (user_id)
    WHERE status = 'PENDING';

-- Admin review queue: pending requests, oldest first.
CREATE INDEX IF NOT EXISTS idx_user_registration_requests_pending_created
    ON user_registration_requests (created_at)
    WHERE status = 'PENDING';

-- "show me this user's registration history"
CREATE INDEX IF NOT EXISTS idx_user_registration_requests_user_id
    ON user_registration_requests (user_id);

-- "what did this administrator review" — partial, since most rows have no reviewer.
CREATE INDEX IF NOT EXISTS idx_user_registration_requests_reviewed_by
    ON user_registration_requests (reviewed_by)
    WHERE reviewed_by IS NOT NULL;

-- Status-filtered listing with the newest first (the default admin list view).
CREATE INDEX IF NOT EXISTS idx_user_registration_requests_status_created
    ON user_registration_requests (status, created_at DESC);

DROP TRIGGER IF EXISTS trg_03_user_registration_requests_updated_at ON user_registration_requests;
CREATE TRIGGER trg_03_user_registration_requests_updated_at
    BEFORE UPDATE ON user_registration_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_registration_requests IS
    'Administrator approval record for a self-registered account. Append-only: rows are never deleted after review.';
COMMENT ON COLUMN user_registration_requests.reviewed_by IS
    'The administrator who approved or declined. Business state, not audit metadata.';

-- Backfill: every account currently waiting for approval needs an open request,
-- otherwise it would be invisible to the new admin review screen and stuck
-- forever. Declined/suspended/archived accounts get no synthetic history.
INSERT INTO user_registration_requests (user_id, status, created_at)
SELECT u.id, 'PENDING', u.created_at
FROM users u
WHERE u.account_status = 'PENDING_APPROVAL'
  AND NOT EXISTS (
      SELECT 1 FROM user_registration_requests r
      WHERE r.user_id = u.id AND r.status = 'PENDING'
  );
