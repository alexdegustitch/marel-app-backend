-- =============================================================================
-- The meal and transport mirror columns go
-- =============================================================================
-- These eight held the same figures as the MEAL_ALLOWANCE and TRANSPORT_ALLOWANCE
-- lines. The line has been the source since step 2; the columns were kept written
-- so the two could be compared before anything was dropped.
--
-- WHAT WAS VERIFIED FIRST, and none of it by assertion:
--   * every one of 2472 lines has been through the component calculator
--     (payroll_adjustments.calculated_at not null) — the maintenance sweep did
--     849 items with 0 failures
--   * payroll-step3-column-vs-line.sql reports 0 real drift for meal and
--     transport across every unarchived item
--   * three employees on three different configurations were edited through the
--     screen and re-verified: a per-day transport, a fixed monthly one, and a
--     foreign employee whose scheme excludes both
--
-- WHAT ALREADY STOPPED USING THEM:
--   * every screen and the payslip PDF read the lines (payrollFigures.ts)
--   * the parameters panel edits the lines, through the adjustments array
--   * PayrollRunItemPatchRequest no longer has mealAllowanceUnitAmount or
--     totalTransportAllowanceAmount, and patch() no longer has their branches
--
-- THE BONUS COLUMNS ARE NOT DROPPED HERE. Nine of them remain, and deliberately:
-- the panel offers the base and the additional bonus as two independently
-- editable figures, and a line has one editable input plus a correction. Editing
-- the base at line level would have to be expressed as a typed total, which the
-- model then treats as having no parts — so the split the panel shows would be
-- destroyed by the next recalculation. That needs a decision about how the line
-- carries two editable parts, not a column drop.
--
-- current_month_telephone is not dropped either: OPEN-12 is unanswered, and until
-- it is, the column is what propagates the phone to next month.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS meal_allowance_count,
    DROP COLUMN IF EXISTS meal_allowance_unit_amount_system,
    DROP COLUMN IF EXISTS meal_allowance_unit_amount,
    DROP COLUMN IF EXISTS meal_allowance_unit_amount_overridden,
    DROP COLUMN IF EXISTS total_meal_allowance_amount,
    DROP COLUMN IF EXISTS total_transport_allowance_amount_system,
    DROP COLUMN IF EXISTS total_transport_allowance_amount,
    DROP COLUMN IF EXISTS total_transport_allowance_amount_overridden;

DO $$
BEGIN
    RAISE NOTICE 'payroll_run_items is down to % columns. The meal and transport figures '
        'live only on their lines now.',
        (SELECT count(*) FROM information_schema.columns WHERE table_name = 'payroll_run_items');
END $$;
