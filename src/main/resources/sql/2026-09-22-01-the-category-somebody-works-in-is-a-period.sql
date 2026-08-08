-- =============================================================================
-- The category somebody normally works in is a period
-- =============================================================================
-- THE CHANGE
-- employees.default_work_category_id was a single column: it said what an
-- employee works in NOW and nothing about what they worked in before. Moving
-- somebody between categories erased the previous answer.
--
-- The authority becomes employee_work_category_periods, dated the same way as
-- employee_employment_periods and employee_compensation_scheme_history.
--
-- IT CHANGES NO MONEY, and that is deliberate. This category only pre-fills what
-- a supervisor is offered when logging work; the calculation reads the category
-- ON THE WORK LOG, never this one. The owner confirmed it explicitly: editing it
-- triggers no recalculation. That is why it gets a plain period table and none
-- of the recalculation machinery the bonus and payroll values need.
--
-- THE COLUMN STAYS, AS A MIRROR
-- employees.default_work_category_id is maintained by trigger from the period in
-- force today, exactly as employment_start_date/end_date mirror the employment
-- periods. Keeping it means the employee projections, DTOs and screens that read
-- it are untouched by this migration.
--
-- ONE HONEST LIMITATION. The mirror is recomputed when a PERIOD is written, not
-- when the clock passes midnight — so a period dated to start next week becomes
-- the mirror only once something writes to the table again. That is acceptable
-- here and nowhere else in this schema: the value seeds a dropdown and reaches
-- no calculation, so being a day late costs a supervisor one extra click. Do not
-- copy this pattern for anything that prices work.
--
-- Re-runnable.
-- =============================================================================

CREATE TABLE IF NOT EXISTS employee_work_category_periods (
    id                    BIGSERIAL   PRIMARY KEY,

    employee_id           BIGINT      NOT NULL,
    work_code_category_id BIGINT      NOT NULL,

    valid_from            DATE        NOT NULL,
    -- Inclusive, like every other period here. NULL = still in force.
    valid_to              DATE,

    note                  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,

    CONSTRAINT fk_ewcp_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_ewcp_category FOREIGN KEY (work_code_category_id) REFERENCES work_code_categories (id),
    CONSTRAINT chk_ewcp_period  CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- Nobody works in two default categories at once. An exclusion constraint rather
-- than check-then-insert, which two concurrent transactions could both pass —
-- the same device ex_eep_no_overlap and ex_ecsh_no_overlap already use.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_ewcp_no_overlap') THEN
        ALTER TABLE employee_work_category_periods
            ADD CONSTRAINT ex_ewcp_no_overlap
            EXCLUDE USING gist (
                employee_id WITH =,
                daterange(valid_from,
                          CASE WHEN valid_to IS NULL THEN NULL ELSE valid_to + 1 END) WITH &&
            ) WHERE (archived_at IS NULL);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ewcp_employee_valid_from
    ON employee_work_category_periods (employee_id, valid_from DESC)
    WHERE archived_at IS NULL;

DROP TRIGGER IF EXISTS trg_03_ewcp_updated_at ON employee_work_category_periods;
CREATE TRIGGER trg_03_ewcp_updated_at
    BEFORE UPDATE ON employee_work_category_periods
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO audit_tables (table_name)
SELECT 'employee_work_category_periods'
WHERE NOT EXISTS (
    SELECT 1 FROM audit_tables t WHERE t.table_name = 'employee_work_category_periods'
);

DROP TRIGGER IF EXISTS trg_audit_logs_employee_work_category_periods ON employee_work_category_periods;
CREATE TRIGGER trg_audit_logs_employee_work_category_periods
    AFTER INSERT OR UPDATE OR DELETE ON employee_work_category_periods
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TABLE employee_work_category_periods IS
    'One row per spell of an employee having a given default work category. The authority for what somebody normally works in on a date. employees.default_work_category_id mirrors the period in force and is maintained by trigger. Affects NO calculation — the work log carries its own category.';


-- =============================================================================
-- Backfill: one open period per employee who has a category today
-- =============================================================================
-- From the column that holds it now, opened at the employment start date so the
-- period covers every work log that already exists. Guarded, so a second run
-- adds nothing.
INSERT INTO employee_work_category_periods (employee_id, work_code_category_id, valid_from, note)
SELECT e.id,
       e.default_work_category_id,
       COALESCE(e.employment_start_date, CURRENT_DATE),
       'Backfilled by 2026-09-22-01 from employees.default_work_category_id.'
FROM employees e
WHERE e.default_work_category_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM employee_work_category_periods p
      WHERE p.employee_id = e.id AND p.archived_at IS NULL
  );


-- =============================================================================
-- The mirror
-- =============================================================================
CREATE OR REPLACE FUNCTION work_category_period_sync_employee_fn() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_employee_id BIGINT := COALESCE(NEW.employee_id, OLD.employee_id);
BEGIN
    -- The period covering TODAY. An employee whose periods have all ended or
    -- been archived goes back to NULL rather than keeping a stale category.
    UPDATE employees e
    SET default_work_category_id = (
        SELECT p.work_code_category_id
        FROM employee_work_category_periods p
        WHERE p.employee_id = v_employee_id
          AND p.archived_at IS NULL
          AND p.valid_from <= CURRENT_DATE
          AND (p.valid_to IS NULL OR p.valid_to >= CURRENT_DATE)
        ORDER BY p.valid_from DESC, p.id DESC
        LIMIT 1
    )
    WHERE e.id = v_employee_id;

    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_04_ewcp_sync_employee ON employee_work_category_periods;
CREATE TRIGGER trg_04_ewcp_sync_employee
    AFTER INSERT OR UPDATE OR DELETE ON employee_work_category_periods
    FOR EACH ROW EXECUTE FUNCTION work_category_period_sync_employee_fn();

COMMENT ON COLUMN employees.default_work_category_id IS
    'Mirror of the employee_work_category_periods row in force today, maintained by trigger. Do not write it directly — write a period. Reads no calculation: it only pre-fills what a supervisor is offered when logging work.';
