-- =============================================================================
-- Who runs a department is a period, not a flag
-- =============================================================================
-- THE RULE
-- A department has a head. Who that is changes over time, and "who was head of
-- Proizvodnja in March" is a question somebody will ask about an old report — so
-- this is a table of dated rows, not a column on departments.
--
-- OPTIONALLY PER SHIFT. shift_id is nullable and the two cases mean different
-- things:
--
--   shift_id IS NULL   Head of the department, across all shifts.
--   shift_id = 2       Head of that department ON the second shift only.
--
-- OVERLAP RULE, and it is deliberate
-- The exclusion constraint keys on (department_id, shift_id) with NULL folded to
-- -1. So:
--   * two department-wide heads at once  -> REFUSED
--   * two heads of the same shift at once -> REFUSED
--   * a department-wide head PLUS a head for shift II -> ALLOWED
-- That last case is the point of making shift_id nullable at all: a general head
-- with shift leads under them is an ordinary arrangement, and a constraint that
-- forbade it would make the nullable column useless.
--
-- An exclusion constraint rather than check-then-insert, which two concurrent
-- transactions could both pass — the same device ex_eep_no_overlap and
-- ex_ecsh_no_overlap already use.
--
-- WHAT IS NOT CONSTRAINED
-- One person may head two departments at once, and may be head while their own
-- employment period is closed. Both happen (a stand-in during a vacancy, a head
-- who left mid-month), and refusing them would cost more than it protects. The
-- application is where such a case gets questioned, not the schema.
--
-- NOT LINKED TO COMPENSATION SCHEME. "Only a STANDARD employee may be head" is
-- a rule the create form applies, not a constraint here: a scheme is itself
-- time-ranged, so a database rule would have to be re-evaluated whenever an
-- unrelated scheme period moved, and would retroactively invalidate a head row
-- that was correct when it was written.
--
-- Re-runnable.
-- =============================================================================

CREATE TABLE IF NOT EXISTS department_head_periods (
    id            BIGSERIAL   PRIMARY KEY,

    department_id BIGINT      NOT NULL,
    employee_id   BIGINT      NOT NULL,

    -- NULL = head of the whole department, whatever the shift.
    shift_id      BIGINT,

    valid_from    DATE        NOT NULL,
    -- NULL = still in post.
    valid_to      DATE,

    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    archived_at   TIMESTAMPTZ,

    CONSTRAINT fk_dhp_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_dhp_employee   FOREIGN KEY (employee_id)   REFERENCES employees (id),
    CONSTRAINT fk_dhp_shift      FOREIGN KEY (shift_id)      REFERENCES shifts (id),
    CONSTRAINT chk_dhp_period    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_dhp_no_overlap') THEN
        ALTER TABLE department_head_periods
            ADD CONSTRAINT ex_dhp_no_overlap
            EXCLUDE USING gist (
                department_id WITH =,
                (coalesce(shift_id, -1)) WITH =,
                daterange(valid_from,
                          CASE WHEN valid_to IS NULL THEN NULL ELSE valid_to + 1 END) WITH &&
            ) WHERE (archived_at IS NULL);
    END IF;
END $$;

-- "Who heads this department today" is the common read.
CREATE INDEX IF NOT EXISTS idx_dhp_department_valid_from
    ON department_head_periods (department_id, valid_from DESC)
    WHERE archived_at IS NULL;

-- "Which departments does this person head" — the employee profile asks it.
CREATE INDEX IF NOT EXISTS idx_dhp_employee_valid_from
    ON department_head_periods (employee_id, valid_from DESC)
    WHERE archived_at IS NULL;

DROP TRIGGER IF EXISTS trg_03_dhp_updated_at ON department_head_periods;
CREATE TRIGGER trg_03_dhp_updated_at
    BEFORE UPDATE ON department_head_periods
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Somebody decided this. Who and when is worth keeping.
INSERT INTO audit_tables (table_name)
SELECT 'department_head_periods'
WHERE NOT EXISTS (
    SELECT 1 FROM audit_tables t WHERE t.table_name = 'department_head_periods'
);

DROP TRIGGER IF EXISTS trg_audit_logs_department_head_periods ON department_head_periods;
CREATE TRIGGER trg_audit_logs_department_head_periods
    AFTER INSERT OR UPDATE OR DELETE ON department_head_periods
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TABLE department_head_periods IS
    'One row per spell of somebody heading a department. The authority for who was in charge on a given date. shift_id NULL means the whole department; a value means that shift only, and both may be in force at once.';

COMMENT ON COLUMN department_head_periods.shift_id IS
    'NULL = head across all shifts. A value = head of that shift only. Folded to -1 in ex_dhp_no_overlap so two department-wide heads still collide.';
