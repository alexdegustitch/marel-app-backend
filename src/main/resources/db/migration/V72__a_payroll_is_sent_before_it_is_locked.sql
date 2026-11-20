-- V72: a payroll is SENT to payroll before it is locked.
--
-- The old chain DRAFT -> APPROVED -> LOCKED used one step for two decisions:
-- "the supervisor finished this month" and "the month was handed to payroll".
-- From now on APPROVED means only the first. A new status, SENT, is the
-- handover itself: the supervisors send a whole month (or one payroll, with a
-- reason) under a password, and only SENT payrolls reach the director, who
-- locks from SENT.
--
--   DRAFT -> APPROVED -> SENT -> LOCKED
--
-- Existing APPROVED rows were approved under the old meaning - already handed
-- over - so they are backfilled to SENT. The director keeps seeing exactly
-- what they see today.

-- === The status learns the new step ==========================================

ALTER TABLE payroll_run_items
    DROP CONSTRAINT IF EXISTS chk_payroll_runs_status;

ALTER TABLE payroll_run_items
    ADD CONSTRAINT chk_payroll_runs_status
        CHECK (status IN ('DRAFT', 'CALCULATED', 'APPROVED', 'SENT', 'LOCKED', 'CANCELLED'));

-- The history keeps SUBMITTED for rows written under the old flow; new
-- approvals record APPROVED and the handover records SENT, so an old step is
-- never re-labelled into something that was not decided at the time.
ALTER TABLE payroll_run_item_handovers
    DROP CONSTRAINT IF EXISTS chk_prih_event;

ALTER TABLE payroll_run_item_handovers
    ADD CONSTRAINT chk_prih_event
        CHECK (event IN ('SUBMITTED', 'APPROVED', 'SENT', 'RETURNED', 'LOCKED', 'UNLOCKED',
                         'CHANGE_REQUESTED', 'CHANGE_ACCEPTED', 'CHANGE_DECLINED'));

COMMENT ON COLUMN payroll_run_item_handovers.event IS
    'One step of the payroll''s history. APPROVED when the supervisor finishes '
    'the month, SENT when it is handed to payroll (SUBMITTED is the legacy '
    'spelling of that handover, kept for rows written before V72); RETURNED '
    'when it goes back; LOCKED and UNLOCKED when payroll freezes the month or '
    'reopens it; CHANGE_* for the request that asks a sent month back.';

-- === When it was approved, and when (and by whom) it was sent ================

ALTER TABLE payroll_run_items
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN sent_at timestamptz,
    ADD COLUMN sent_by bigint REFERENCES users (id);

COMMENT ON COLUMN payroll_run_items.approved_at IS
    'When the supervisor last marked the month finished (DRAFT -> APPROVED). '
    'Cleared when the payroll returns to DRAFT. The sent-payrolls screen sorts by it.';
COMMENT ON COLUMN payroll_run_items.sent_at IS
    'When the payroll was handed to the payroll office (-> SENT). Cleared when '
    'it returns to DRAFT. Mirrors locked_at one step earlier.';
COMMENT ON COLUMN payroll_run_items.sent_by IS
    'Who performed the handover - the supervisor who sent the month or this one payroll.';

-- The director''s working set: sent but not yet locked. Small by nature, so a
-- partial index keeps the dashboard and the sent-payrolls page off a full scan.
CREATE INDEX idx_pri_sent ON payroll_run_items (payroll_run_id) WHERE status = 'SENT';

-- === Backfill: what was APPROVED was, in the old sense, already sent =========

-- set_updated_at would stamp every backfilled row with the migration moment,
-- which would shuffle "last activity" views for months nobody touched.
ALTER TABLE payroll_run_items DISABLE TRIGGER trg_03_payroll_run_items_updated_at;

-- The moment it was last handed over, from the history where one exists.
WITH last_submit AS (
    SELECT DISTINCT ON (payroll_run_item_id)
           payroll_run_item_id, occurred_at, actor_id
    FROM payroll_run_item_handovers
    WHERE event = 'SUBMITTED'
    ORDER BY payroll_run_item_id, occurred_at DESC
)
UPDATE payroll_run_items pri
SET approved_at = ls.occurred_at,
    sent_at     = ls.occurred_at,
    sent_by     = ls.actor_id
FROM last_submit ls
WHERE ls.payroll_run_item_id = pri.id
  AND pri.status IN ('APPROVED', 'LOCKED');

-- Months handed over before the history existed (pre-V20): the best moment on
-- record is the row's own clock.
UPDATE payroll_run_items
SET approved_at = COALESCE(approved_at, updated_at, created_at),
    sent_at     = COALESCE(sent_at, updated_at, created_at)
WHERE status IN ('APPROVED', 'LOCKED')
  AND sent_at IS NULL;

UPDATE payroll_run_items
SET status = 'SENT'
WHERE status = 'APPROVED';

ALTER TABLE payroll_run_items ENABLE TRIGGER trg_03_payroll_run_items_updated_at;
