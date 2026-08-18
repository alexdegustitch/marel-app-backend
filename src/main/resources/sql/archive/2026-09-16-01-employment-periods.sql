-- =============================================================================
-- Employment becomes a history of periods
-- =============================================================================
-- WHY
-- An employee can leave and come back. Today that is one start date and one end
-- date on the employee row, so a rehire either overwrites the first spell — the
-- history is gone — or gets a second employee record, and then their work,
-- payroll and audit trail are split across two people who are one person.
--
-- WHAT PROBATION HAS TO DO WITH IT
-- probation_end_date is currently GENERATED ALWAYS AS
-- (employment_start_date + norm_grace_days). Once employment_start_date means
-- "the start of the LATEST spell" (owner's rule), that generated column would
-- hand every returning employee a fresh 30-day probation — the exact opposite of
-- the rule that a rehire gets norm_grace_days = 0 by default.
--
-- So probation moves onto the period, where the arithmetic is over two columns of
-- the SAME row and is honest again. On the period it stays GENERATED: it cannot
-- drift, it is queryable, and no code can forget it.
--
-- On employees it becomes a PLAIN column maintained by trigger, because its value
-- now comes from another table and a generated column cannot be written.
--
-- OWNER'S RULES, ENCODED HERE
--   * a new period defaults to norm_grace_days = 0 — a rehire serves no new
--     probation unless somebody says so;
--   * employees.norm_grace_days stays, default 30, and is what the FIRST period
--     is opened with;
--   * "date of employment" is the start of the LATEST period, not the first;
--   * the compensation scheme carries on across a break — this file does not
--     touch employee_compensation_scheme_history.
--
-- COMPATIBILITY. employees.employment_start_date, employment_end_date and
-- probation_end_date all stay, as trigger-maintained mirrors of the latest
-- period. 47 places read them — screens, filters, sorting, projections — and none
-- has to change. The periods table is the authority; the columns are a view of it
-- that SQL and the existing code can still use.
--
-- Re-runnable.
-- =============================================================================

CREATE TABLE IF NOT EXISTS employee_employment_periods (
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id        BIGINT      NOT NULL,

    started_on         DATE        NOT NULL,
    -- NULL = still employed. Inclusive, like every other period in this schema.
    ended_on           DATE,

    -- 0 by default: a returning employee serves no new probation unless an
    -- administrator says otherwise. The FIRST period is opened with the
    -- employee's own norm_grace_days instead — see the backfill and
    -- EmployeeService.
    norm_grace_days    INT         NOT NULL DEFAULT 0,

    -- Arithmetic over two columns of this row, so it stays generated: it cannot
    -- drift from them and it can be queried and indexed.
    probation_end_date DATE        GENERATED ALWAYS AS (started_on + norm_grace_days) STORED,

    note               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    archived_at        TIMESTAMPTZ,

    CONSTRAINT fk_eep_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT chk_eep_period  CHECK (ended_on IS NULL OR ended_on >= started_on),
    CONSTRAINT chk_eep_grace   CHECK (norm_grace_days >= 0)
);

-- Nobody is employed twice at once. An exclusion constraint rather than a
-- check-then-insert, which two concurrent transactions could both pass — the same
-- device ex_ecsh_no_overlap and ex_epvh_no_overlap already use.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_eep_no_overlap') THEN
        ALTER TABLE employee_employment_periods
            ADD CONSTRAINT ex_eep_no_overlap
            EXCLUDE USING gist (
                employee_id WITH =,
                daterange(started_on,
                          CASE WHEN ended_on IS NULL THEN NULL ELSE ended_on + 1 END) WITH &&
            ) WHERE (archived_at IS NULL);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_eep_employee_started
    ON employee_employment_periods (employee_id, started_on DESC)
    WHERE archived_at IS NULL;

DROP TRIGGER IF EXISTS trg_03_eep_updated_at ON employee_employment_periods;
CREATE TRIGGER trg_03_eep_updated_at
    BEFORE UPDATE ON employee_employment_periods
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE employee_employment_periods IS
    'One row per spell of employment. The authority for when somebody worked here and for their probation. employees.employment_start_date / employment_end_date / probation_end_date mirror the LATEST period and are maintained by trigger.';


-- =============================================================================
-- Backfill: one period per employee, from the columns that hold it today
-- =============================================================================
INSERT INTO employee_employment_periods
    (employee_id, started_on, ended_on, norm_grace_days, note)
SELECT e.id, e.employment_start_date, e.employment_end_date,
       COALESCE(e.norm_grace_days, 0),
       'Backfilled by 2026-09-16-01 from employees.employment_start_date/end_date.'
FROM employees e
WHERE e.employment_start_date IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM employee_employment_periods p
                  WHERE p.employee_id = e.id AND p.archived_at IS NULL);


-- =============================================================================
-- probation_end_date on employees stops being generated
-- =============================================================================
-- DROP EXPRESSION keeps the values that are already there and makes the column
-- ordinary, so the trigger below can write it. Nothing is recomputed by this
-- statement.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = 'employees'::regclass
                 AND attname = 'probation_end_date'
                 AND attgenerated = 's') THEN
        ALTER TABLE employees ALTER COLUMN probation_end_date DROP EXPRESSION;
    END IF;
END $$;

COMMENT ON COLUMN employees.probation_end_date IS
    'Mirror of the LATEST employment period, maintained by trg_eep_sync_employee. Not generated: its value comes from employee_employment_periods, not from this row. Read ProbationPolicy rather than this column when deciding anything.';
COMMENT ON COLUMN employees.employment_start_date IS
    'Mirror of the LATEST employment period start. employee_employment_periods is the authority.';
COMMENT ON COLUMN employees.employment_end_date IS
    'Mirror of the LATEST employment period end; NULL while employed. employee_employment_periods is the authority.';
COMMENT ON COLUMN employees.norm_grace_days IS
    'Default probation length for the FIRST period only. A later period carries its own, defaulting to 0 — a rehire serves no new probation unless somebody says so.';


-- =============================================================================
-- The mirror, maintained where it cannot be forgotten
-- =============================================================================
CREATE OR REPLACE FUNCTION employment_period_sync_employee_fn() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_employee_id BIGINT := COALESCE(NEW.employee_id, OLD.employee_id);
BEGIN
    -- The LATEST period, by start date: "date of employment" is the start of the
    -- current spell, not the first one ever (owner's rule). An employee whose
    -- every period has been archived keeps NULLs rather than a stale spell.
    UPDATE employees e
    SET employment_start_date = p.started_on,
        employment_end_date   = p.ended_on,
        probation_end_date    = p.probation_end_date
    FROM (
        SELECT started_on, ended_on, probation_end_date
        FROM employee_employment_periods
        WHERE employee_id = v_employee_id AND archived_at IS NULL
        ORDER BY started_on DESC, id DESC
        LIMIT 1
    ) p
    WHERE e.id = v_employee_id;

    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_eep_sync_employee ON employee_employment_periods;
CREATE TRIGGER trg_eep_sync_employee
    AFTER INSERT OR UPDATE OR DELETE ON employee_employment_periods
    FOR EACH ROW EXECUTE FUNCTION employment_period_sync_employee_fn();


-- =============================================================================
-- Sync once, so the mirror and the authority agree from this moment on
-- =============================================================================
UPDATE employees e
SET employment_start_date = p.started_on,
    employment_end_date   = p.ended_on,
    probation_end_date    = p.probation_end_date
FROM (
    SELECT DISTINCT ON (employee_id)
           employee_id, started_on, ended_on, probation_end_date
    FROM employee_employment_periods
    WHERE archived_at IS NULL
    ORDER BY employee_id, started_on DESC, id DESC
) p
WHERE e.id = p.employee_id
  AND (e.employment_start_date IS DISTINCT FROM p.started_on
    OR e.employment_end_date   IS DISTINCT FROM p.ended_on
    OR e.probation_end_date    IS DISTINCT FROM p.probation_end_date);


-- =============================================================================
-- Audit — a period decides what somebody is paid and whether they may work
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'employee_employment_periods'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'employee_employment_periods');

DROP TRIGGER IF EXISTS trg_audit_logs_employee_employment_periods ON employee_employment_periods;
CREATE TRIGGER trg_audit_logs_employee_employment_periods
    AFTER INSERT OR UPDATE OR DELETE ON employee_employment_periods
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();


-- =============================================================================
-- Diagnostics — expect zero rows from both
-- =============================================================================
-- The mirror disagrees with the latest period:
--
--   SELECT e.id FROM employees e
--   JOIN LATERAL (SELECT started_on, ended_on, probation_end_date
--                 FROM employee_employment_periods
--                 WHERE employee_id = e.id AND archived_at IS NULL
--                 ORDER BY started_on DESC, id DESC LIMIT 1) p ON TRUE
--   WHERE e.employment_start_date IS DISTINCT FROM p.started_on
--      OR e.employment_end_date   IS DISTINCT FROM p.ended_on
--      OR e.probation_end_date    IS DISTINCT FROM p.probation_end_date;
--
-- An employee with a start date but no period:
--
--   SELECT id FROM employees e WHERE e.employment_start_date IS NOT NULL
--   AND NOT EXISTS (SELECT 1 FROM employee_employment_periods p
--                   WHERE p.employee_id = e.id AND p.archived_at IS NULL);
-- =============================================================================
