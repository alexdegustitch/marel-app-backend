-- =============================================================================
-- A shift can have more than one break
-- =============================================================================
-- WHAT CHANGES
--   employee_shift_break_periods (V67) learns to hold SEVERAL breaks per
--   shift, each with its own dated history, and lets an employee opt OUT of a
--   default break:
--
--     break_no    — which break this spell is about: 1 = prva pauza, 2 =
--                   druga, … The ordinal is the break's identity: an
--                   employee's row for (shift, break_no) overrides the
--                   default row for the same (shift, break_no); an employee
--                   row with a break_no no default uses is an EXTRA break of
--                   their own.
--     suppressed  — employee rows only: this person does not take that
--                   default break (dated like everything else). Times are
--                   NULL on such a row; there is no window to state.
--
--   Resolution on a date, PER break_no: the employee's row covering the date
--   wins (suppressed → no such break that day); then the default row; then
--   nothing. The breaks of a day are the union over break_no.
--
-- WHAT THIS DOES NOT TOUCH
--   Existing rows become break 1 and keep meaning exactly what they meant.
--   work_shifts rows and their logs stay the material truth — a moved or
--   added window reaches only logs written after the change. History and
--   audit are untouched: spells stay dated, the audit trigger stays on.
--
--   · Rollback:
--       ALTER TABLE employee_shift_break_periods
--           DROP CONSTRAINT ex_esbp_no_overlap,
--           DROP CONSTRAINT chk_esbp_window,
--           DROP CONSTRAINT chk_esbp_suppressed_is_personal,
--           DROP COLUMN break_no,
--           DROP COLUMN suppressed;
--       (then restore V67's chk_esbp_times_differ, NOT NULLs and exclusion —
--        only valid while no second break and no suppression row exists)
-- =============================================================================

ALTER TABLE employee_shift_break_periods
    ADD COLUMN IF NOT EXISTS break_no smallint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS suppressed boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN employee_shift_break_periods.break_no IS
    'Which break of the shift this spell is about (1 = prva pauza, …). The ordinal is the break''s identity: an employee row overrides the default row with the same (shift_id, break_no).';
COMMENT ON COLUMN employee_shift_break_periods.suppressed IS
    'Employee rows only: this person does not take that default break while the spell covers the date. Times are NULL on a suppressed row.';

ALTER TABLE employee_shift_break_periods
    ALTER COLUMN start_time DROP NOT NULL,
    ALTER COLUMN end_time DROP NOT NULL;

-- The window rule, suppression-aware: a suppressed spell states no window at
-- all; a real one states both ends, different (end < start = across midnight).
ALTER TABLE employee_shift_break_periods
    DROP CONSTRAINT IF EXISTS chk_esbp_times_differ;
ALTER TABLE employee_shift_break_periods
    DROP CONSTRAINT IF EXISTS chk_esbp_window;
ALTER TABLE employee_shift_break_periods
    ADD CONSTRAINT chk_esbp_window CHECK (
        (suppressed AND start_time IS NULL AND end_time IS NULL)
        OR (NOT suppressed AND start_time IS NOT NULL AND end_time IS NOT NULL
            AND start_time <> end_time)
    );

-- Only a person can refuse a break; a shift's default is stated or closed,
-- never suppressed.
ALTER TABLE employee_shift_break_periods
    DROP CONSTRAINT IF EXISTS chk_esbp_suppressed_is_personal;
ALTER TABLE employee_shift_break_periods
    ADD CONSTRAINT chk_esbp_suppressed_is_personal CHECK (
        NOT suppressed OR employee_id IS NOT NULL
    );

ALTER TABLE employee_shift_break_periods
    DROP CONSTRAINT IF EXISTS chk_esbp_break_no;
ALTER TABLE employee_shift_break_periods
    ADD CONSTRAINT chk_esbp_break_no CHECK (break_no >= 1);

-- One spell in force at a time PER BREAK, per (shift, employee-or-default) —
-- V67's guard, one column wider.
ALTER TABLE employee_shift_break_periods
    DROP CONSTRAINT IF EXISTS ex_esbp_no_overlap;
ALTER TABLE employee_shift_break_periods
    ADD CONSTRAINT ex_esbp_no_overlap EXCLUDE USING gist (
        shift_id WITH =,
        break_no WITH =,
        COALESCE(employee_id, ('-1'::integer)::bigint) WITH =,
        daterange(valid_from,
                  CASE WHEN valid_to IS NULL THEN NULL::date ELSE (valid_to + 1) END)
            WITH &&
    ) WHERE (archived_at IS NULL);
