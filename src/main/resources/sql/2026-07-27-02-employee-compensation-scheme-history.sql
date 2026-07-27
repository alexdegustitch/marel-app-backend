-- =============================================================================
-- employee_compensation_scheme_history — which scheme applied to whom, WHEN
-- =============================================================================
-- The scheme is a date-effective *period*, not a column on employees, because an
-- employee moves between schemes and payroll for work already done must not
-- change when they do. Resolution is always by WORK DATE — never by "now", never
-- by the payroll run date.
--
-- DATE SEMANTICS
--   valid_from   inclusive
--   valid_until  inclusive; NULL means open-ended
-- So work on 2026-08-01 belongs to a period ending 2026-07-31's SUCCESSOR, and a
-- period [.., 2026-07-31] and a period [2026-08-01, ..] do not overlap. The
-- exclusion constraint below encodes exactly that by converting to a half-open
-- daterange with `valid_until + 1` as the exclusive upper bound.
--
-- OVERLAP PREVENTION
-- A GiST exclusion constraint, not application logic, because two concurrent
-- transactions can each pass a "does an overlapping period exist?" SELECT and
-- then both INSERT. btree_gist is already installed (work_code_categories uses
-- the same pattern). Archived rows are excluded from the constraint so a
-- corrected period can be archived and replaced.
--
-- =============================================================================
-- BACKFILL POLICY — read this before changing it
-- =============================================================================
-- employees.is_foreigner is the only field that reliably identifies the current
-- foreign calculation group, so it is used for the backfill — and ONLY for the
-- backfill. Nothing in the calculation path reads it, and the column keeps its
-- original personnel meaning.
--
-- The FOREIGN_FIXED_COEFFICIENT period deliberately does NOT start at
-- employment_start_date. That policy did not exist before this migration: every
-- employee, foreign or not, was in fact paid under the standard rules until now.
-- Backfilling the foreign policy to an employee's hire date would rewrite years
-- of history to claim a rule that was never applied, and would change the result
-- of any recalculation of an old shift.
--
-- So:
--   every employee            STANDARD from employment_start_date
--   is_foreigner employees    STANDARD closed on the day before the cutover,
--                             then FOREIGN_FIXED_COEFFICIENT from the cutover
--
-- CUTOVER DATE: 2026-08-01 — the first day of the first payroll month that has
-- not started. At the time of writing the latest work shift is 2026-07-04 and
-- the 2026-07 payroll run is still DRAFT, so no in-progress or historical period
-- is affected. Change this constant only in a follow-up migration, never by
-- editing this one after it has run.
--
-- Re-runnable: IF NOT EXISTS on DDL; the seed INSERTs are guarded by NOT EXISTS
-- on (employee, scheme, valid_from).
-- =============================================================================

CREATE TABLE IF NOT EXISTS employee_compensation_scheme_history (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id            BIGINT      NOT NULL,
    compensation_scheme_id BIGINT      NOT NULL,
    valid_from             DATE        NOT NULL,
    valid_until            DATE,
    note                   TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    archived_at            TIMESTAMPTZ,

    CONSTRAINT fk_ecsh_employee
        FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    -- RESTRICT: a scheme that some period points at is history and must not be
    -- deletable out from under it. Schemes are retired with is_active = false.
    CONSTRAINT fk_ecsh_scheme
        FOREIGN KEY (compensation_scheme_id) REFERENCES compensation_schemes (id) ON DELETE RESTRICT,

    CONSTRAINT chk_ecsh_validity
        CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

-- The same employee cannot have two non-archived periods whose date ranges
-- overlap. Enforced in the database so a concurrent "change scheme" cannot race
-- two valid-looking periods into existence.
ALTER TABLE employee_compensation_scheme_history
    DROP CONSTRAINT IF EXISTS ex_ecsh_no_overlap;
ALTER TABLE employee_compensation_scheme_history
    ADD CONSTRAINT ex_ecsh_no_overlap
    EXCLUDE USING gist (
        employee_id WITH =,
        daterange(valid_from,
                  CASE WHEN valid_until IS NULL THEN NULL ELSE valid_until + 1 END) WITH &&
    ) WHERE (archived_at IS NULL);

-- The resolution query: one employee, one work date.
CREATE INDEX IF NOT EXISTS idx_ecsh_employee_period
    ON employee_compensation_scheme_history (employee_id, valid_from, valid_until)
    WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ecsh_scheme
    ON employee_compensation_scheme_history (compensation_scheme_id);

DROP TRIGGER IF EXISTS trg_03_ecsh_updated_at ON employee_compensation_scheme_history;
CREATE TRIGGER trg_03_ecsh_updated_at
    BEFORE UPDATE ON employee_compensation_scheme_history
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE employee_compensation_scheme_history IS
    'Date-effective employee -> compensation scheme periods. Resolved by WORK DATE, never by now() or the payroll run date. Historical rows are never rewritten to change the current scheme; a change closes the open period and inserts a new one.';
COMMENT ON COLUMN employee_compensation_scheme_history.valid_until IS
    'Inclusive last day of the period; NULL = open-ended. The exclusion constraint converts to a half-open range with valid_until + 1.';


-- =============================================================================
-- Backfill
-- =============================================================================
DO $$
DECLARE
    v_cutover      CONSTANT DATE := DATE '2026-08-01';
    v_standard_id  BIGINT;
    v_foreign_id   BIGINT;
BEGIN
    SELECT id INTO v_standard_id FROM compensation_schemes WHERE code = 'STANDARD';
    SELECT id INTO v_foreign_id  FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT';

    IF v_standard_id IS NULL OR v_foreign_id IS NULL THEN
        RAISE EXCEPTION 'compensation_schemes seeds missing; run 2026-07-27-01 first';
    END IF;

    -- 1. STANDARD period for every employee, from their hire date.
    --    For a foreign employee hired before the cutover the period is closed on
    --    the day before it; anyone hired on/after the cutover gets no STANDARD
    --    period at all (it would be empty and would violate chk_ecsh_validity).
    INSERT INTO employee_compensation_scheme_history
        (employee_id, compensation_scheme_id, valid_from, valid_until, note)
    SELECT e.id,
           v_standard_id,
           e.employment_start_date,
           CASE WHEN e.is_foreigner THEN v_cutover - 1 ELSE NULL END,
           'Backfilled by 2026-07-27-02: every employee was calculated under the standard rules before compensation schemes existed.'
    FROM employees e
    WHERE NOT (e.is_foreigner AND e.employment_start_date >= v_cutover)
      AND NOT EXISTS (
          SELECT 1 FROM employee_compensation_scheme_history h
          WHERE h.employee_id = e.id
            AND h.compensation_scheme_id = v_standard_id
            AND h.valid_from = e.employment_start_date
      );

    -- 2. FOREIGN_FIXED_COEFFICIENT from the cutover for the current foreign
    --    group — or from the hire date for anyone hired on/after the cutover.
    INSERT INTO employee_compensation_scheme_history
        (employee_id, compensation_scheme_id, valid_from, valid_until, note)
    SELECT e.id,
           v_foreign_id,
           GREATEST(e.employment_start_date, v_cutover),
           NULL,
           'Backfilled by 2026-07-27-02 from employees.is_foreigner. Verify against the real payroll policy before the first run of this period.'
    FROM employees e
    WHERE e.is_foreigner
      AND NOT EXISTS (
          SELECT 1 FROM employee_compensation_scheme_history h
          WHERE h.employee_id = e.id
            AND h.compensation_scheme_id = v_foreign_id
            AND h.valid_from = GREATEST(e.employment_start_date, v_cutover)
      );

    RAISE NOTICE 'Compensation scheme backfill complete: % employees, % on FOREIGN_FIXED_COEFFICIENT from %',
        (SELECT count(*) FROM employees),
        (SELECT count(*) FROM employees WHERE is_foreigner),
        v_cutover;
END $$;


-- =============================================================================
-- Audit — scheme changes are a payroll-relevant decision and must be traceable
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'employee_compensation_scheme_history'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'employee_compensation_scheme_history');

DROP TRIGGER IF EXISTS trg_audit_logs_employee_compensation_scheme_history ON employee_compensation_scheme_history;
CREATE TRIGGER trg_audit_logs_employee_compensation_scheme_history
    AFTER INSERT OR UPDATE OR DELETE ON employee_compensation_scheme_history
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
