-- =============================================================================
-- What a scheme says about a payroll line, beyond "allowed or not"
-- =============================================================================
-- A single is_allowed boolean can express "foreign employees get no meal
-- allowance". It cannot express the case next to it: "commercial employees see
-- their bonus, and it is always zero, and nobody may edit it". That line is
-- INCLUDED, VISIBLE, and NOT CALCULATED — three different answers where the
-- boolean has one.
--
-- So the rule gains the fields that say the rest. Every one is NULLABLE, and NULL
-- means "inherit from the category". A scheme states only what it changes, which
-- is what keeps 39 rows readable instead of 39 rows of duplicated defaults.
--
--   effective value = COALESCE(rule.x, category.x)
--
-- calculation_mode is the exception: it is NOT NULL with an explicit INHERIT
-- value, because "this scheme does not calculate this line" and "this scheme has
-- no opinion" are genuinely different and both need saying.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE payroll_adjustment_category_scheme_rules
    -- INHERIT — use the category's calculation_key
    -- ZERO    — the line exists and is always 0; the calculator is not run
    -- MANUAL  — no automatic value; whatever a user enters stands
    ADD COLUMN IF NOT EXISTS calculation_mode      VARCHAR(20) NOT NULL DEFAULT 'INHERIT',

    -- NULL = inherit from payroll_adjustment_categories.
    ADD COLUMN IF NOT EXISTS visible_in_ui         BOOLEAN,
    ADD COLUMN IF NOT EXISTS visible_in_pdf        BOOLEAN,
    ADD COLUMN IF NOT EXISTS show_when_zero        BOOLEAN,
    ADD COLUMN IF NOT EXISTS editable_input        VARCHAR(20),
    ADD COLUMN IF NOT EXISTS allow_total_override  BOOLEAN,
    ADD COLUMN IF NOT EXISTS required_manual_input BOOLEAN,

    -- Merged over the category's parameters, this scheme winning. How a seasonal
    -- scheme caps transport at one arrival a day without a second calculator.
    ADD COLUMN IF NOT EXISTS parameters_override   JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE payroll_adjustment_category_scheme_rules
    DROP CONSTRAINT IF EXISTS chk_pacsr_calculation_mode;
ALTER TABLE payroll_adjustment_category_scheme_rules
    ADD CONSTRAINT chk_pacsr_calculation_mode
    CHECK (calculation_mode IN ('INHERIT', 'ZERO', 'MANUAL'));

ALTER TABLE payroll_adjustment_category_scheme_rules
    DROP CONSTRAINT IF EXISTS chk_pacsr_editable_input;
ALTER TABLE payroll_adjustment_category_scheme_rules
    ADD CONSTRAINT chk_pacsr_editable_input
    CHECK (editable_input IS NULL
           OR editable_input IN ('NONE', 'AMOUNT', 'UNIT_AMOUNT', 'QUANTITY', 'CORRECTION'));

-- An excluded line must not also claim to calculate something. Without this a rule
-- can say "not allowed" and "run the meal calculator" at once, and which one wins
-- is then a property of whichever code reads it first.
ALTER TABLE payroll_adjustment_category_scheme_rules
    DROP CONSTRAINT IF EXISTS chk_pacsr_excluded_is_inert;
ALTER TABLE payroll_adjustment_category_scheme_rules
    ADD CONSTRAINT chk_pacsr_excluded_is_inert
    CHECK (is_allowed = TRUE OR calculation_mode IN ('INHERIT', 'ZERO'));

COMMENT ON COLUMN payroll_adjustment_category_scheme_rules.calculation_mode IS
    'INHERIT uses the category calculation_key; ZERO means the line exists but is never calculated; MANUAL means no automatic value. NOT NULL because "does not calculate" and "has no opinion" are different statements.';
COMMENT ON COLUMN payroll_adjustment_category_scheme_rules.visible_in_ui IS
    'NULL inherits from the category. A scheme states only what it changes.';
