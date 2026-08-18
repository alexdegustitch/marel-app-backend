-- =============================================================================
-- Edit policy on payroll lines, and room for a calculated result
-- =============================================================================
-- THE PROBLEM THIS SOLVES
-- "What may a user change on this line" is currently one boolean, allow_override,
-- and it does not describe what actually happens. Three different things are all
-- called an override today:
--
--   the meal PRICE is edited and the system recomputes the total   (an INPUT)
--   the bonus gets a CORRECTION added to the system figure          (an INPUT)
--   the transport TOTAL is typed in and the formula is bypassed     (an OVERRIDE)
--
-- Collapsing them means the flag cannot express "the count is the system's, the
-- price is yours" — which is exactly the meal allowance. So the policy splits in
-- two: editable_input names the one input a user may change, and
-- allow_total_override says whether the final amount may be typed in directly.
--
-- Diagnostic Q12 (2026-07-31) shows allow_override is currently FALSE on both
-- MEAL_ALLOWANCE and TRANSPORT_ALLOWANCE while both are edited through the patch
-- endpoint every day, because that endpoint goes through the item columns where
-- the flag is never read. The flag is decoration today. These columns are what
-- replaces it.
--
-- Re-runnable: IF NOT EXISTS on every ADD COLUMN, constraints dropped first.
-- =============================================================================

ALTER TABLE payroll_adjustment_categories
    -- Which single INPUT a user may change. The formula still runs afterwards.
    --   NONE        nothing; the line is the system's
    --   AMOUNT      the whole amount (a manual line: Ostalo, Korekcija, Fiksni LD)
    --   UNIT_AMOUNT the unit price; the system still supplies the count (meal)
    --   QUANTITY    the count; the system still supplies the price
    --   CORRECTION  a delta added to the system figure (bonus)
    ADD COLUMN IF NOT EXISTS editable_input        VARCHAR(20)  NOT NULL DEFAULT 'NONE',

    -- Whether the final amount may be typed in directly, bypassing the formula.
    -- Independent of editable_input: transport allows no input edit at all, yet an
    -- administrator may still set the total by decision.
    ADD COLUMN IF NOT EXISTS allow_total_override  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Show the line even when the amount is 0. This is what makes "commercial
    -- employees see their bonus, and it is zero" expressible.
    ADD COLUMN IF NOT EXISTS show_when_zero        BOOLEAN      NOT NULL DEFAULT TRUE,

    -- A manual line that MUST be filled in before the item can be locked.
    ADD COLUMN IF NOT EXISTS required_manual_input BOOLEAN      NOT NULL DEFAULT FALSE;

ALTER TABLE payroll_adjustment_categories
    DROP CONSTRAINT IF EXISTS chk_pac_editable_input;
ALTER TABLE payroll_adjustment_categories
    ADD CONSTRAINT chk_pac_editable_input
    CHECK (editable_input IN ('NONE', 'AMOUNT', 'UNIT_AMOUNT', 'QUANTITY', 'CORRECTION'));

COMMENT ON COLUMN payroll_adjustment_categories.editable_input IS
    'The one INPUT a user may change; the formula still runs. Distinct from allow_total_override, which bypasses the formula entirely.';
COMMENT ON COLUMN payroll_adjustment_categories.allow_total_override IS
    'Whether the final amount may be typed in directly. Requires a reason from phase 4 onward.';


ALTER TABLE payroll_adjustments
    -- A delta the user adds ON TOP of the system figure — the bonus correction.
    -- Not an override: the system value stays visible and the two are summed.
    ADD COLUMN IF NOT EXISTS correction_amount  NUMERIC(38, 2) NOT NULL DEFAULT 0,

    -- Mandatory from phase 4c, when is_overridden narrows to mean a hard total
    -- override only. Added now so the column exists before the semantics change.
    ADD COLUMN IF NOT EXISTS override_reason    TEXT,

    -- Distinguishes "nobody has entered anything" from "somebody entered 0".
    -- Without it a required manual line of 0 is indistinguishable from an empty
    -- one, and the item would either lock when it should not or never lock at all.
    ADD COLUMN IF NOT EXISTS has_manual_input   BOOLEAN NOT NULL DEFAULT FALSE,

    ADD COLUMN IF NOT EXISTS status             VARCHAR(30) NOT NULL DEFAULT 'CALCULATED',

    -- What the calculator was given: shift counts, rates, the reason for a zero.
    -- An unexplained zero on a payslip is indistinguishable from a bug.
    ADD COLUMN IF NOT EXISTS calculation_inputs JSONB NOT NULL DEFAULT '{}'::jsonb,

    ADD COLUMN IF NOT EXISTS calculated_at      TIMESTAMPTZ;

-- -----------------------------------------------------------------------------
-- Repair a column Hibernate may have created first
-- -----------------------------------------------------------------------------
-- ddl-auto=update adds a nullable column with no default when it sees a new
-- entity field. If it got here before this migration did, calculation_inputs
-- already exists — and ADD COLUMN IF NOT EXISTS then skips the whole clause,
-- NOT NULL and DEFAULT included. The column would be left nullable and
-- defaultless, silently, and the very first calculator write would put a NULL in
-- the field that is supposed to explain a zero.
--
-- Backfill then tighten. Safe when the column was created correctly: the UPDATE
-- matches nothing and the ALTERs are no-ops.
UPDATE payroll_adjustments SET calculation_inputs = '{}'::jsonb
WHERE calculation_inputs IS NULL;

ALTER TABLE payroll_adjustments
    ALTER COLUMN calculation_inputs SET DEFAULT '{}'::jsonb,
    ALTER COLUMN calculation_inputs SET NOT NULL;

ALTER TABLE payroll_adjustments
    DROP CONSTRAINT IF EXISTS chk_pa_status;
ALTER TABLE payroll_adjustments
    ADD CONSTRAINT chk_pa_status
    CHECK (status IN ('CALCULATED', 'PENDING_INPUT', 'MANUAL', 'OVERRIDDEN', 'ERROR'));

COMMENT ON COLUMN payroll_adjustments.correction_amount IS
    'A delta added to the system figure. The system value stays visible; this is not an override.';
COMMENT ON COLUMN payroll_adjustments.has_manual_input IS
    'TRUE once a user has entered a value, including an explicit 0. Separates "empty" from "zero".';
COMMENT ON COLUMN payroll_adjustments.calculation_inputs IS
    'What the calculator was given, including the reason for a zero. An unexplained zero is indistinguishable from a bug.';


-- =============================================================================
-- Edit policy per category
-- =============================================================================
-- section_code and impact_code are NOT touched here. Production has drifted from
-- the original seed (Q8/Q12) and section_code decides which total a line reaches,
-- so changing one moves money.
-- =============================================================================
UPDATE payroll_adjustment_categories c
SET editable_input       = v.editable_input,
    allow_total_override = v.allow_total_override,
    required_manual_input = v.required_manual_input
FROM (VALUES
    -- The system counts the meals; the administrator may reprice one.
    ('MEAL_ALLOWANCE',               'UNIT_AMOUNT', FALSE, FALSE),
    -- The system counts the arrivals AND knows the rate; nothing is edited piece
    -- by piece, but the total may be set by decision.
    ('TRANSPORT_ALLOWANCE',          'NONE',        TRUE,  FALSE),
    -- Base figure plus a correction, or a final figure typed in outright.
    ('MONTHLY_BONUS',                'CORRECTION',  TRUE,  FALSE),
    -- Purely manual lines.
    ('FIXED_SALARY',                 'AMOUNT',      FALSE, FALSE),
    ('POSITIVE_NEGATIVE_CORRECTION', 'AMOUNT',      FALSE, FALSE),
    ('OTHER',                        'AMOUNT',      FALSE, FALSE),
    ('INSTALLMENT',                  'AMOUNT',      FALSE, FALSE),
    ('PHONE_CURRENT_MONTH',          'AMOUNT',      FALSE, FALSE),
    ('PHONE_PREVIOUS_MONTH',         'AMOUNT',      FALSE, FALSE),
    ('PAID_PART_1',                  'AMOUNT',      FALSE, FALSE),
    ('PAID_PART_2',                  'AMOUNT',      FALSE, FALSE),
    -- Display mirrors: no automatic rule is defined for them yet, and none is
    -- invented here.
    ('PAID_PREVIOUS_PERIOD',         'NONE',        FALSE, FALSE),
    ('PREVIOUS_BALANCE',             'NONE',        FALSE, FALSE)
) AS v(code, editable_input, allow_total_override, required_manual_input)
WHERE c.code = v.code;


-- =============================================================================
-- calculation_key: every key must have a calculator, or the run fails loudly
-- =============================================================================
-- D6 makes an unknown calculation_key a hard error rather than a silent zero. So
-- a key may only name an algorithm that actually exists.
--
-- Three keys in the catalogue name algorithms that were never written and that
-- nothing has ever executed (Q7 confirms this):
--
--   MONTHLY_BONUS_FROM_COMPONENTS   the bonus is entered by hand today; what the
--                                   automatic base should be is an open business
--                                   question, not something to infer here
--   PAID_PREVIOUS_PERIOD            a display mirror reaching no total
--   PREVIOUS_BALANCE                likewise; previous_net_payable_amount is what
--                                   actually carries the balance
--
-- Writing an algorithm for them would be inventing a business rule. They become
-- MANUAL — which is what the system genuinely does today — and the automatic
-- rules stay recorded as open questions in the migration plan.
--
-- TRANSPORT_BY_WORK_DAYS is renamed because D3 changed what it counts: qualifying
-- SHIFTS, not work days. Keeping the old name would describe an algorithm the
-- code no longer implements.
UPDATE payroll_adjustment_categories
SET calculation_key = 'TRANSPORT_BY_QUALIFYING_SHIFTS'
WHERE code = 'TRANSPORT_ALLOWANCE';

UPDATE payroll_adjustment_categories
SET calculation_key = 'MANUAL'
WHERE calculation_key IS NULL
   OR calculation_key IN ('MONTHLY_BONUS_FROM_COMPONENTS',
                          'PAID_PREVIOUS_PERIOD',
                          'PREVIOUS_BALANCE');

-- Fail the migration rather than the payroll run if anything unexpected is left.
DO $$
DECLARE
    v_unknown TEXT;
BEGIN
    SELECT string_agg(DISTINCT calculation_key, ', ')
      INTO v_unknown
    FROM payroll_adjustment_categories
    WHERE archived_at IS NULL
      AND is_active
      AND calculation_key IS NOT NULL
      -- Must mirror CalculationKeys.java. The last three are registered by a
      -- LATER migration (2026-08-20-01), which is why they are listed in a file
      -- that predates them: once that migration has run, re-running this one
      -- would otherwise trip its own guard on keys whose calculators do exist.
      -- Re-running is the normal recovery path here — these files are applied by
      -- hand — so a guard that only passes on a virgin database is a trap.
      AND calculation_key NOT IN ('MANUAL',
                                  'MEAL_BY_ELIGIBLE_SHIFTS',
                                  'TRANSPORT_BY_QUALIFYING_SHIFTS',
                                  'MONTHLY_BONUS_FROM_RULES',
                                  'PAID_PREVIOUS_PERIOD_SUM',
                                  'PREVIOUS_BALANCE_CARRIED');

    IF v_unknown IS NOT NULL THEN
        RAISE EXCEPTION 'calculation_key(s) with no calculator: %. Register a calculator '
            'or set the category to MANUAL before this migration can run.', v_unknown;
    END IF;
END $$;
