-- =============================================================================
-- total_deductions_amount goes
-- =============================================================================
-- WHAT IT HELD
-- The sum of every applied line whose category has impact_code DEDUCTION_MINUS.
-- Today that is exactly four categories, and they do not belong in one figure:
--
--   INSTALLMENT           a real deduction
--   PHONE_PREVIOUS_MONTH  a real deduction
--   PAID_PART_2           NOT a deduction — money already PAID to the employee
--   PHONE_CURRENT_MONTH   reaches no total at all; the current month's phone is
--                         deducted NEXT month as PHONE_PREVIOUS_MONTH (OPEN-12)
--
-- So the column added a payment and a not-yet-charged phone to two deductions.
-- Seven unarchived items carry a non-zero value and not one of them could be
-- reconciled against the payslip it belongs to.
--
-- WHY DROPPING RATHER THAN DISPLAYING
-- It was computed, returned by the API and typed in the frontend, and rendered
-- nowhere — which is the only reason nobody had complained. Putting it on screen
-- would have shown a number that does not mean what its label says. "Ukupna
-- odbijanja" is a figure the business defines, and once defined it is one sum
-- over payroll_adjustments, computed where it is shown — the same way the meal,
-- transport and bonus figures are read now. Nothing is lost by dropping it, and
-- an unreconcilable total is not a starting point.
--
-- NO GUARD, and deliberately: this column feeds no other figure. totalNetEarnings
-- sums IMPACT_GROSS_PLUS, previouslyPaidAmount sums the SETTLEMENTS section, and
-- neither reads this. Verified before writing.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS total_deductions_amount;

DO $$
BEGIN
    RAISE NOTICE 'payroll_run_items is down to % columns.',
        (SELECT count(*) FROM information_schema.columns WHERE table_name = 'payroll_run_items');
END $$;
