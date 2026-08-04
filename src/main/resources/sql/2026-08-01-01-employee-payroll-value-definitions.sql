-- =============================================================================
-- employee_payroll_value_definitions — the catalogue of per-employee values
-- =============================================================================
-- WHAT THIS IS FOR
-- A payroll calculator needs numbers that belong to ONE employee: their hourly
-- rate, their transport rate, their fixed salary. Today those live as columns on
-- `employees` — a single mutable value with no history — so raising somebody's
-- rate in September silently reprices every earlier month the next time it is
-- recalculated. That is the bug this table and its history table close.
--
-- WHY A CATALOGUE RATHER THAN A FREE-TEXT KEY
-- A generic key/value store lets a calculator invent a key, and an invented key
-- silently resolves to nothing, which in payroll means silently paying zero. A
-- value must reference a definition that already exists, so an unknown key is a
-- foreign-key violation at write time instead of a missing amount at pay time.
--
-- WHY payroll_adjustment_category_id IS NULLABLE
-- HOURLY_RATE is an INPUT to the calculation, not a line on the payslip: it
-- prices `payroll_run_item_categories`, which are work categories, not adjustment
-- categories. TRANSPORT_FIXED_MONTHLY, by contrast, belongs to a line. Forcing a category
-- on every definition would mean inventing a fake payslip line for the hourly
-- rate purely to satisfy a constraint.
--
-- Re-runnable: IF NOT EXISTS on DDL, seeds guarded by NOT EXISTS on `code`.
-- =============================================================================

CREATE TABLE IF NOT EXISTS employee_payroll_value_definitions (
    id                             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                           VARCHAR(100) NOT NULL,
    name                           VARCHAR(150) NOT NULL,
    description                    TEXT,

    -- NUMERIC | BOOLEAN | TEXT. Every value today is NUMERIC; the other two exist
    -- so a future limit flag or a note does not need a schema change. The history
    -- table enforces that only the matching column is populated.
    value_type                     VARCHAR(20) NOT NULL,

    -- RSD, PERCENT, COUNT, HOUR... Display and sanity only; no arithmetic reads it.
    unit_code                      VARCHAR(30),

    -- NULL when the value is a calculation input rather than a payslip line.
    payroll_adjustment_category_id BIGINT,

    -- System definitions are referenced by calculator code and must not be
    -- archived through the UI. Enforced in the service, recorded here.
    is_system                      BOOLEAN NOT NULL DEFAULT TRUE,
    is_active                      BOOLEAN NOT NULL DEFAULT TRUE,

    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ,
    archived_at                    TIMESTAMPTZ,

    -- RESTRICT, not CASCADE: a definition some employee has a value for is
    -- history. Categories are retired with is_active = false, never deleted.
    CONSTRAINT fk_epvd_category
        FOREIGN KEY (payroll_adjustment_category_id)
        REFERENCES payroll_adjustment_categories (id) ON DELETE RESTRICT,

    CONSTRAINT chk_epvd_code       CHECK (length(trim(code)) > 0),
    CONSTRAINT chk_epvd_name       CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_epvd_value_type CHECK (value_type IN ('NUMERIC', 'BOOLEAN', 'TEXT')),
    CONSTRAINT chk_epvd_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_active = TRUE))
);

-- -----------------------------------------------------------------------------
-- Repair the table when ddl-auto=update created it first
-- -----------------------------------------------------------------------------
-- CREATE TABLE IF NOT EXISTS skips the WHOLE definition when the table is already
-- there, defaults and CHECK constraints included. Hibernate makes the table from
-- the entity: it gets the columns and the NOT NULLs, but no DEFAULT now() (it
-- sets created_at in Java) and none of the named constraints. The seed INSERT
-- below does not list created_at, so on such a table it fails with
-- "null value in column created_at" — which is exactly how this was found.
--
-- Restated here so the file converges on the same schema either way. No-ops when
-- the CREATE above did the work.
ALTER TABLE employee_payroll_value_definitions
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN is_system  SET DEFAULT TRUE,
    ALTER COLUMN is_active  SET DEFAULT TRUE;

ALTER TABLE employee_payroll_value_definitions
    DROP CONSTRAINT IF EXISTS chk_epvd_code,
    DROP CONSTRAINT IF EXISTS chk_epvd_name,
    DROP CONSTRAINT IF EXISTS chk_epvd_value_type,
    DROP CONSTRAINT IF EXISTS chk_epvd_no_reactivate,
    DROP CONSTRAINT IF EXISTS fk_epvd_category;
ALTER TABLE employee_payroll_value_definitions
    ADD CONSTRAINT chk_epvd_code       CHECK (length(trim(code)) > 0),
    ADD CONSTRAINT chk_epvd_name       CHECK (length(trim(name)) > 0),
    ADD CONSTRAINT chk_epvd_value_type CHECK (value_type IN ('NUMERIC', 'BOOLEAN', 'TEXT')),
    ADD CONSTRAINT chk_epvd_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_active = TRUE)),
    ADD CONSTRAINT fk_epvd_category
        FOREIGN KEY (payroll_adjustment_category_id)
        REFERENCES payroll_adjustment_categories (id) ON DELETE RESTRICT;

-- Hibernate's own foreign keys on this table, if it made any. They duplicate the
-- named ones just added — same columns, same behaviour, a second trigger doing
-- identical work — and their generated names make the schema unreadable. Matched
-- on the generated-name shape (fk + 20+ hex-ish characters), which no
-- hand-written constraint in this project uses.
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'employee_payroll_value_definitions'::regclass
          AND contype = 'f'
          AND conname ~ '^fk[a-z0-9]{20,}$'
    LOOP
        EXECUTE format('ALTER TABLE employee_payroll_value_definitions DROP CONSTRAINT %I', r.conname);
        RAISE NOTICE 'Dropped auto-generated foreign key %; fk_epvd_category replaces it.', r.conname;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_epvd_code
    ON employee_payroll_value_definitions (code);

-- Referenced by the composite foreign key in the history table, which is what
-- makes "the value column matches the declared type" a database guarantee rather
-- than a convention a trigger has to maintain.
-- Added only when absent, NOT dropped and re-created. Once 2026-08-01-02 has run,
-- fk_epvh_definition_type in the history table depends on this constraint, and a
-- plain DROP then fails with "other objects depend on it" — so the file would
-- only be re-runnable until the next migration made it not so. CASCADE would
-- "fix" that by quietly taking the composite foreign key with it and leaving the
-- type guarantee gone.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'employee_payroll_value_definitions'::regclass
          AND conname = 'uq_epvd_id_value_type'
    ) THEN
        ALTER TABLE employee_payroll_value_definitions
            ADD CONSTRAINT uq_epvd_id_value_type UNIQUE (id, value_type);
    END IF;
END $$;

DROP TRIGGER IF EXISTS trg_03_epvd_updated_at ON employee_payroll_value_definitions;
CREATE TRIGGER trg_03_epvd_updated_at
    BEFORE UPDATE ON employee_payroll_value_definitions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE employee_payroll_value_definitions IS
    'Catalogue of per-employee payroll values a calculator may reference. A calculator may not invent a key: an unregistered code is a foreign-key violation at write time, not a silent zero at pay time.';
COMMENT ON COLUMN employee_payroll_value_definitions.payroll_adjustment_category_id IS
    'NULL when the value is a calculation input rather than a payslip line. HOURLY_RATE prices work categories and has no adjustment line of its own.';


-- =============================================================================
-- Seeds — resolved by code, never by id
-- =============================================================================
INSERT INTO employee_payroll_value_definitions
    (code, name, description, value_type, unit_code, payroll_adjustment_category_id, is_system)
SELECT v.code, v.name, v.description, v.value_type, v.unit_code,
       (SELECT c.id FROM payroll_adjustment_categories c WHERE c.code = v.category_code),
       TRUE
FROM (VALUES
    ('HOURLY_RATE',      'Satnica',
     'Prices payroll_run_item_categories. A calculation input, not a payslip line, so it has no adjustment category.',
     'NUMERIC', 'RSD',   NULL),
    ('TRANSPORT_FIXED_MONTHLY', 'Fiksna mesečna nadoknada za prevoz',
     'A fixed monthly amount, paid whole whatever the employee worked. Having this value is what puts an employee on the fixed mode; everyone else is paid per worked day from app_settings.transport_allowance_per_day.',
     'NUMERIC', 'RSD',   'TRANSPORT_ALLOWANCE'),
    ('FIXED_LD_AMOUNT',  'Fiksni lični dohodak',
     'A fixed monthly amount that replaces the hourly calculation.',
     'NUMERIC', 'RSD',   'FIXED_SALARY'),
    ('TELEPHONE_AMOUNT', 'Iznos za telefon',
     'The employee''s standing telephone deduction.',
     'NUMERIC', 'RSD',   'PHONE_CURRENT_MONTH')
    -- BONUS_PERCENTAGE was seeded here and removed by 2026-08-29-01. The bonus is
    -- not a percentage of anything: it is a flat amount from the employee's bonus
    -- category, resolved for the period through employees_bonus_history, plus a
    -- tier from bonus_eligibility_rules. Nothing multiplied by a per-employee
    -- percentage and no value was ever written for it.
) AS v(code, name, description, value_type, unit_code, category_code)
WHERE NOT EXISTS (
    SELECT 1 FROM employee_payroll_value_definitions d WHERE d.code = v.code
);

-- Link definitions to their payslip line, separately and idempotently.
--
-- Separate because the link depends on payroll_adjustment_categories being
-- populated, and it is not in every environment: the catalogue is seeded by
-- 2026-04-25-payroll-model-restructure.sql, which sits below the integration-test
-- baseline cutoff. Doing it inline would leave the link silently NULL wherever the
-- categories arrive later, and NULL there means "this value has no payslip line",
-- which is a real and different statement.
--
-- Re-running this migration repairs the link once the categories exist.
UPDATE employee_payroll_value_definitions d
SET payroll_adjustment_category_id = c.id
FROM payroll_adjustment_categories c
WHERE d.payroll_adjustment_category_id IS NULL
  AND d.archived_at IS NULL
  AND c.code = CASE d.code
      WHEN 'TRANSPORT_FIXED_MONTHLY' THEN 'TRANSPORT_ALLOWANCE'
      WHEN 'FIXED_LD_AMOUNT'  THEN 'FIXED_SALARY'
      WHEN 'TELEPHONE_AMOUNT' THEN 'PHONE_CURRENT_MONTH'
      -- HOURLY_RATE is absent on purpose: it is a calculation input, not a line.
  END;


-- =============================================================================
-- Audit — a per-employee rate is a payroll decision and must be traceable
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'employee_payroll_value_definitions'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'employee_payroll_value_definitions');

DROP TRIGGER IF EXISTS trg_audit_logs_employee_payroll_value_definitions
    ON employee_payroll_value_definitions;
CREATE TRIGGER trg_audit_logs_employee_payroll_value_definitions
    AFTER INSERT OR UPDATE OR DELETE ON employee_payroll_value_definitions
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
