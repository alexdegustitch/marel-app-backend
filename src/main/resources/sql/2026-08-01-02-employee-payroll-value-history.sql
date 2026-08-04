-- =============================================================================
-- employee_payroll_value_history — what an employee's value was, and WHEN
-- =============================================================================
-- DATE SEMANTICS — INCLUSIVE, decided 2026-07-31
--   valid_from   inclusive
--   valid_until  INCLUSIVE last day; NULL means open-ended
-- Identical to employee_compensation_scheme_history, on purpose: two tables that
-- answer "what applied on date X" must not answer it with different conventions.
-- The exclusion constraint converts to a half-open daterange with `valid_until + 1`.
--
-- This deliberately does NOT match app_settings, which uses a half-open
-- tstzrange. That is not an oversight — app_settings is a CONTINUOUS type, where
-- an inclusive upper bound has no representable "next value" and would force
-- microsecond arithmetic at every boundary. Half-open is correct for continuous
-- types, inclusive for discrete ones. See D2a in the migration plan.
--
-- VALUES ARE NEVER UPDATED IN PLACE
-- A change closes the open period and opens a new one, in one transaction. That
-- is what makes recalculating an old month reproduce the number that was actually
-- paid — the whole reason this table exists.
--
-- HOW THE VALUE COLUMN IS KEPT HONEST
-- `value_type` is denormalised from the definition and bound to it by a COMPOSITE
-- foreign key, so it cannot drift. A plain CHECK then enforces that exactly the
-- matching column is populated. No trigger, and no way to write a numeric value
-- against a TEXT definition.
--
-- Re-runnable: IF NOT EXISTS on DDL, constraints dropped before being added.
-- =============================================================================

CREATE TABLE IF NOT EXISTS employee_payroll_value_history (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id         BIGINT      NOT NULL,
    value_definition_id BIGINT      NOT NULL,

    -- Mirrors the definition, held there by fk_epvh_definition_type below.
    value_type          VARCHAR(20) NOT NULL,

    numeric_value       NUMERIC(38, 6),
    boolean_value       BOOLEAN,
    text_value          TEXT,

    valid_from          DATE        NOT NULL,
    valid_until         DATE,

    note                TEXT,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    archived_at         TIMESTAMPTZ,

    CONSTRAINT fk_epvh_employee
        FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,

    -- Composite: pins BOTH the definition and its declared type in one reference,
    -- so value_type cannot be set to something the definition does not declare.
    CONSTRAINT fk_epvh_definition_type
        FOREIGN KEY (value_definition_id, value_type)
        REFERENCES employee_payroll_value_definitions (id, value_type)
        ON UPDATE CASCADE ON DELETE RESTRICT,

    CONSTRAINT fk_epvh_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,

    CONSTRAINT chk_epvh_validity
        CHECK (valid_until IS NULL OR valid_until >= valid_from),

    -- Exactly one value column, matching the declared type. Written out rather
    -- than as a num_nonnulls trick so that a violation names the type it expected.
    CONSTRAINT chk_epvh_value_matches_type CHECK (
        (value_type = 'NUMERIC' AND numeric_value IS NOT NULL
             AND boolean_value IS NULL AND text_value IS NULL)
     OR (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
             AND numeric_value IS NULL AND text_value IS NULL)
     OR (value_type = 'TEXT'    AND text_value IS NOT NULL
             AND length(trim(text_value)) > 0
             AND numeric_value IS NULL AND boolean_value IS NULL)
    )
);

-- -----------------------------------------------------------------------------
-- Repair the table when ddl-auto=update created it first
-- -----------------------------------------------------------------------------
-- Same reasoning as in 2026-08-01-01: CREATE TABLE IF NOT EXISTS skips the whole
-- definition, so a Hibernate-made table has the columns but neither the default
-- nor the constraints. Here that matters most for chk_epvh_value_matches_type
-- and the composite fk_epvh_definition_type — without them the "the value column
-- matches the declared type" guarantee is not in the database at all, and a
-- mismatched row would be accepted and only surface at pay time.
--
-- No-ops when the CREATE above did the work.
ALTER TABLE employee_payroll_value_history
    ALTER COLUMN created_at SET DEFAULT now();

ALTER TABLE employee_payroll_value_history
    DROP CONSTRAINT IF EXISTS chk_epvh_validity,
    DROP CONSTRAINT IF EXISTS chk_epvh_value_matches_type,
    DROP CONSTRAINT IF EXISTS fk_epvh_employee,
    DROP CONSTRAINT IF EXISTS fk_epvh_definition_type,
    DROP CONSTRAINT IF EXISTS fk_epvh_created_by;
ALTER TABLE employee_payroll_value_history
    ADD CONSTRAINT chk_epvh_validity
        CHECK (valid_until IS NULL OR valid_until >= valid_from),
    ADD CONSTRAINT chk_epvh_value_matches_type CHECK (
        (value_type = 'NUMERIC' AND numeric_value IS NOT NULL
             AND boolean_value IS NULL AND text_value IS NULL)
     OR (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
             AND numeric_value IS NULL AND text_value IS NULL)
     OR (value_type = 'TEXT'    AND text_value IS NOT NULL
             AND length(trim(text_value)) > 0
             AND numeric_value IS NULL AND boolean_value IS NULL)
    ),
    ADD CONSTRAINT fk_epvh_employee
        FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_epvh_definition_type
        FOREIGN KEY (value_definition_id, value_type)
        REFERENCES employee_payroll_value_definitions (id, value_type)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_epvh_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL;

DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'employee_payroll_value_history'::regclass
          AND contype = 'f'
          AND conname ~ '^fk[a-z0-9]{20,}$'
    LOOP
        EXECUTE format('ALTER TABLE employee_payroll_value_history DROP CONSTRAINT %I', r.conname);
        RAISE NOTICE 'Dropped auto-generated foreign key %; the fk_epvh_* constraints replace it.', r.conname;
    END LOOP;
END $$;


-- One value per (employee, definition) at any moment. In the database, not in the
-- service: two concurrent "change the rate" requests can each pass a SELECT check
-- and then both INSERT. Archived rows are excluded so a mistake can be archived
-- and replaced.
ALTER TABLE employee_payroll_value_history
    DROP CONSTRAINT IF EXISTS ex_epvh_no_overlap;
ALTER TABLE employee_payroll_value_history
    ADD CONSTRAINT ex_epvh_no_overlap
    EXCLUDE USING gist (
        employee_id WITH =,
        value_definition_id WITH =,
        daterange(valid_from,
                  CASE WHEN valid_until IS NULL THEN NULL ELSE valid_until + 1 END) WITH &&
    ) WHERE (archived_at IS NULL);

-- The batch lookup: every employee in a payroll run, one period.
CREATE INDEX IF NOT EXISTS idx_epvh_employee_period
    ON employee_payroll_value_history (employee_id, valid_from DESC)
    WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_epvh_definition
    ON employee_payroll_value_history (value_definition_id);

DROP TRIGGER IF EXISTS trg_03_epvh_updated_at ON employee_payroll_value_history;
CREATE TRIGGER trg_03_epvh_updated_at
    BEFORE UPDATE ON employee_payroll_value_history
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE employee_payroll_value_history IS
    'Date-effective per-employee payroll values. Resolved by PAYROLL PERIOD, never by now(). Rows are never updated in place: a change closes the open period and opens a new one.';
COMMENT ON COLUMN employee_payroll_value_history.valid_until IS
    'INCLUSIVE last day the value applies; NULL = open-ended. Same convention as employee_compensation_scheme_history. The exclusion constraint converts to a half-open range with valid_until + 1.';
COMMENT ON COLUMN employee_payroll_value_history.value_type IS
    'Denormalised from the definition and bound to it by the composite foreign key, so it cannot drift. chk_epvh_value_matches_type then enforces which value column is populated.';


-- =============================================================================
-- Audit
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'employee_payroll_value_history'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'employee_payroll_value_history');

DROP TRIGGER IF EXISTS trg_audit_logs_employee_payroll_value_history
    ON employee_payroll_value_history;
CREATE TRIGGER trg_audit_logs_employee_payroll_value_history
    AFTER INSERT OR UPDATE OR DELETE ON employee_payroll_value_history
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
