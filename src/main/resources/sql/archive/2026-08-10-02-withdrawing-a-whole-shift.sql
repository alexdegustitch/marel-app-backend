-- =============================================================================
-- Withdrawing a whole shift
-- =============================================================================
-- THE CHANGE
-- A shift entered by mistake could not be taken back. There was no delete, and
-- is_active — which several list queries already filter on — was never set to
-- FALSE by anything, so all 41 rows read TRUE. Half a mechanism, wired to
-- nothing.
--
-- Archiving is the operation, not deleting: a shift's work logs are what
-- somebody was paid for, and they stay readable. Only a shift with NOTHING on it
-- may be deleted outright, and that is a row that never reached a payroll.
--
-- WHO AND WHEN, because withdrawing a day of work is a decision and not a
-- correction. is_active is kept in step so the queries already filtering on it
-- keep working unchanged.
--
-- THE TWO CONSTRAINTS THAT HAD TO CHANGE, AND WHY IT MATTERS
-- Both currently count archived rows, and both would then refuse the obvious
-- next step — entering the shift again, correctly:
--
--   ex_work_shifts_no_overlap    an archived shift would still occupy its hours
--   uq_work_shifts_...work_date  an archived shift would still hold its day
--
-- So a mistake, once withdrawn, would block its own correction. Both are
-- recreated to count only live rows. This is the part of the migration to read
-- twice: it DROPs and re-CREATEs a constraint, and on a large table the
-- recreation takes a lock while it builds.
--
-- IMPACT
-- Two nullable columns and two constraint definitions. No row changes meaning:
-- every existing shift has archived_at NULL and stays exactly as live as it is
-- today. Reversible — drop the columns and restore the two constraints without
-- the WHERE clause.
-- =============================================================================

BEGIN;

ALTER TABLE work_shifts
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archived_by BIGINT REFERENCES users (id);

COMMENT ON COLUMN work_shifts.archived_at IS
    'When the shift was withdrawn. NULL means live. Its work logs are kept: '
    'they are what somebody was paid for.';

-- ── The overlap rule now applies between LIVE shifts only ────────────────────
ALTER TABLE work_shifts DROP CONSTRAINT ex_work_shifts_no_overlap;

ALTER TABLE work_shifts
    ADD CONSTRAINT ex_work_shifts_no_overlap
    EXCLUDE USING gist (
        employee_id WITH =,
        tstzrange(start_at, end_at) WITH &&
    ) WHERE (archived_at IS NULL);

-- ── And so does one-shift-per-employee-per-day ───────────────────────────────
-- A UNIQUE CONSTRAINT cannot be partial, so it becomes a partial unique INDEX.
-- Same guarantee for live rows, none for withdrawn ones.
ALTER TABLE work_shifts DROP CONSTRAINT uq_work_shifts_employee_shift_work_date;

CREATE UNIQUE INDEX uq_work_shifts_employee_shift_work_date
    ON work_shifts (employee_id, shift_id, work_date)
    WHERE archived_at IS NULL;

CREATE INDEX idx_work_shifts_archived ON work_shifts (archived_at)
    WHERE archived_at IS NOT NULL;

COMMIT;
