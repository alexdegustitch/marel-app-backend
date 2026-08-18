-- =============================================================================
-- One source of truth for the earnings side, and a narrower is_overridden
-- =============================================================================
-- WHAT CHANGES IN THE ARITHMETIC
-- Until now totalNetEarnings was:
--
--   categories + item.total_meal_allowance_amount + item.total_transport_allowance_amount
--              + SUM(adjustments in section ADDITIONS, with MEAL and TRANSPORT
--                    excluded BY CODE so they were not counted twice)
--
-- Meal and transport were booked in two places at once and kept in step by hand.
-- From now the adjustment rows are the only source and the earnings side sums by
-- IMPACT rather than by section:
--
--   categories + SUM(applied adjustments WHERE impact_code = 'GROSS_PLUS')
--
-- That set is exactly {MEAL_ALLOWANCE, TRANSPORT_ALLOWANCE, FIXED_SALARY,
-- MONTHLY_BONUS, OTHER, POSITIVE_NEGATIVE_CORRECTION} — the same money as before,
-- reached without the special cases. Verified by PayrollGoldenSnapshotIT.
--
-- WHY THE SETTLEMENTS SIDE IS *NOT* SWITCHED TO IMPACT CODES
-- It would change what people are paid. previouslyPaid is today SUM(section
-- SETTLEMENTS) = INSTALLMENT, PHONE_PREVIOUS_MONTH, PAID_PART_1, PAID_PART_2.
-- Switching to DEDUCTION_MINUS + PAYMENT_MINUS would ALSO pull in
-- PHONE_CURRENT_MONTH and PAID_PREVIOUS_PERIOD, which reach no total at all today
-- — the current month's phone is deducted next month as PHONE_PREVIOUS_MONTH, and
-- PAID_PREVIOUS_PERIOD is a display mirror.
--
-- Making those two start reducing somebody's pay is a business decision, not a
-- refactor. It is recorded as OPEN-12 and the settlements filter stays on
-- section_code until it is answered.
--
-- Re-runnable. The legacy item columns are NOT dropped — they stay a mirror until
-- phase 7, after one verified payroll cycle on the new model.
-- =============================================================================

-- =============================================================================
-- is_overridden now means ONE thing: the final amount was typed in
-- =============================================================================
-- It used to be set whenever quantity, unit price OR amount differed from the
-- system value, which conflated "the administrator repriced a meal and the system
-- recomputed the total" with "the administrator bypassed the formula". D7 needs
-- those apart: the first is an edit to an INPUT, the second is an override.
--
-- Only rows whose final amount equals the system amount can be proved not to be
-- overrides. Everything else keeps its flag — reinterpreting it would be guessing
-- at somebody's intent years after the fact.
UPDATE payroll_adjustments
SET is_overridden = FALSE
WHERE is_overridden = TRUE
  AND system_amount IS NOT NULL
  AND amount = system_amount;

-- chk_pa_override_reason IS DELIBERATELY NOT ADDED HERE.
--
-- D7 requires a reason on every hard override, and the column for it exists. But
-- the patch endpoint has no field to carry one: an administrator typing a
-- transport total today has no way to say why, so the constraint would reject a
-- perfectly ordinary edit and there would be no UI able to satisfy it.
--
-- Enforcing a rule before anyone can comply with it does not make the data
-- honest, it just makes the feature unusable. The constraint lands in phase 6,
-- together with the request field that feeds it. Until then the reason is
-- optional and the audit trail carries who and when.

COMMENT ON COLUMN payroll_adjustments.is_overridden IS
    'The final amount was typed in and the formula bypassed. NOT set by editing a permitted input such as the meal unit price — compare unit_amount against system_unit_amount for that.';


-- =============================================================================
-- Display snapshot: a payslip must not be reordered by a later config change
-- =============================================================================
-- Only what changes a NUMBER or a POSITION on the document is snapshotted. The
-- name deliberately is not: it is resolved through
-- payroll_adjustment_category_translations, and freezing it would mean correcting
-- a Serbian label required rewriting every historical row.
ALTER TABLE payroll_adjustments
    ADD COLUMN IF NOT EXISTS section_code_snapshot   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS impact_code_snapshot    VARCHAR(40),
    ADD COLUMN IF NOT EXISTS sort_order_snapshot     INTEGER,
    ADD COLUMN IF NOT EXISTS visible_in_ui_snapshot  BOOLEAN,
    ADD COLUMN IF NOT EXISTS visible_in_pdf_snapshot BOOLEAN,
    ADD COLUMN IF NOT EXISTS show_when_zero_snapshot BOOLEAN;

-- Backfill from the catalogue as it stands today. These rows were calculated
-- under exactly this configuration, so it is the correct snapshot for them.
UPDATE payroll_adjustments a
SET section_code_snapshot   = c.section_code,
    impact_code_snapshot    = c.impact_code,
    sort_order_snapshot     = c.sort_order,
    visible_in_ui_snapshot  = c.visible_in_ui,
    visible_in_pdf_snapshot = c.visible_in_pdf,
    show_when_zero_snapshot = c.show_when_zero
FROM payroll_adjustment_categories c
WHERE c.id = a.payroll_adjustment_category_id
  AND a.impact_code_snapshot IS NULL;


-- =============================================================================
-- The legacy item columns are now a mirror
-- =============================================================================
COMMENT ON COLUMN payroll_run_items.total_meal_allowance_amount IS
    'MIRROR ONLY since 2026-08-12. The payroll_adjustments row for MEAL_ALLOWANCE is the source of truth; this column is kept in step for one payroll cycle and dropped in phase 7.';
COMMENT ON COLUMN payroll_run_items.total_transport_allowance_amount IS
    'MIRROR ONLY since 2026-08-12. The payroll_adjustments row for TRANSPORT_ALLOWANCE is the source of truth; dropped in phase 7.';
COMMENT ON COLUMN payroll_run_items.total_bonus_amount IS
    'MIRROR ONLY since 2026-08-12. The payroll_adjustments row for MONTHLY_BONUS is the source of truth; dropped in phase 7.';
