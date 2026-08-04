-- =============================================================================
-- Three lines get their algorithm back
-- =============================================================================
-- Phase 3 set MONTHLY_BONUS, PAID_PREVIOUS_PERIOD and PREVIOUS_BALANCE to MANUAL
-- because their calculation_keys named algorithms that had never been written,
-- and writing one would have meant inventing a business rule.
--
-- The rules were not missing, they were undocumented. Recorded here so the next
-- person does not have to ask again.
--
-- MONTHLY_BONUS — two independent parts
--   base:  the employee's bonus category (employees_bonus_history ->
--          bonus_categories.bonus_amount), paid IN FULL only if they worked at
--          least bonus_min_hours_rules.min_num_hours for that month. Below the
--          threshold it is nothing, not a proportion.
--   tier:  bonus_eligibility_rules holds several hour thresholds per month; the
--          highest one reached adds its bonus_value on top.
--   Both are keyed by PERIOD, so a month with no rule pays no bonus rather than
--   borrowing another month's numbers.
--
-- PAID_PREVIOUS_PERIOD — the sum of what has already been settled:
--   INSTALLMENT + PHONE_PREVIOUS_MONTH + PAID_PART_1 + PAID_PART_2
--   which is exactly what recalculateSummaryTotals already computes as
--   previously_paid_amount by summing section SETTLEMENTS. The line SHOWS that
--   total; the balance is then total earnings − this figure.
--   It stays in section SETTLEMENTS_SUM. Counting it among the settlements would
--   deduct everything twice.
--
-- PREVIOUS_BALANCE — last month's closing balance, already on the item as
--   previous_net_payable_amount and already inside net_payable_amount. Section
--   BALANCE, for the same reason.
--
-- PHONE_CURRENT_MONTH is confirmed as a RECORDING field and stays MANUAL: the
-- amount entered this month is deducted NEXT month, as PHONE_PREVIOUS_MONTH. It
-- correctly reaches no total in the month it is entered.
--
-- Re-runnable. Ends by refusing any key without a calculator.
-- =============================================================================

UPDATE payroll_adjustment_categories
SET calculation_key = 'MONTHLY_BONUS_FROM_RULES'
WHERE code = 'MONTHLY_BONUS';

UPDATE payroll_adjustment_categories
SET calculation_key = 'PAID_PREVIOUS_PERIOD_SUM'
WHERE code = 'PAID_PREVIOUS_PERIOD';

UPDATE payroll_adjustment_categories
SET calculation_key = 'PREVIOUS_BALANCE_CARRIED'
WHERE code = 'PREVIOUS_BALANCE';

-- The bonus is now calculated, so the manual-entry policy changes with it: a
-- correction on top stays available, a typed-in final figure stays available, and
-- the base is the system's.
UPDATE payroll_adjustment_categories
SET editable_input       = 'CORRECTION',
    allow_total_override = TRUE
WHERE code = 'MONTHLY_BONUS';

-- These two show a total. Nothing to edit, and nothing to override.
UPDATE payroll_adjustment_categories
SET editable_input       = 'NONE',
    allow_total_override = FALSE
WHERE code IN ('PAID_PREVIOUS_PERIOD', 'PREVIOUS_BALANCE');


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
      AND calculation_key NOT IN ('MANUAL',
                                  'MEAL_BY_ELIGIBLE_SHIFTS',
                                  'TRANSPORT_BY_QUALIFYING_SHIFTS',
                                  'MONTHLY_BONUS_FROM_RULES',
                                  'PAID_PREVIOUS_PERIOD_SUM',
                                  'PREVIOUS_BALANCE_CARRIED');

    IF v_unknown IS NOT NULL THEN
        RAISE EXCEPTION 'calculation_key(s) with no calculator: %.', v_unknown;
    END IF;
END $$;
