-- =============================================================================
-- total_gross_earnings never held anything
-- =============================================================================
-- 0.00 in all 849 unarchived items, and no code path ever computed it — the
-- golden-snapshot suite has said so in a comment since phase 2. The payslip
-- screen printed it as "Ukupna zarada (+)" all the same, so every employee's
-- total earnings read as nothing.
--
-- WHAT THE LABEL MEANS, AND WHY net IS THE ANSWER
-- This application has no tax model: no contributions, no gross-to-net. There is
-- one earnings figure, total_net_earnings — categories plus every applied
-- GROSS_PLUS line — and "Ukupna zarada (+)" is a description of exactly that.
-- The screen shows it now.
--
-- If "bruto" was ever meant as earnings BEFORE deductions, that is
-- total_net_earnings + total_deductions_amount and it is one line to add. It was
-- asked and not answered, and a column nothing fills is not evidence of an
-- intention.
--
-- Nothing edited it: there is no changeKey for it anywhere in the UI, and the
-- branch in EmployeePayroll that mapped an edit of it to totalNetEarnings was
-- unreachable.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_nonzero INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'payroll_run_items' AND column_name = 'total_gross_earnings') THEN
        RAISE NOTICE 'total_gross_earnings is already gone.';
        RETURN;
    END IF;

    EXECUTE 'SELECT count(*) FROM payroll_run_items WHERE COALESCE(total_gross_earnings, 0) <> 0'
        INTO v_nonzero;

    IF v_nonzero > 0 THEN
        RAISE EXCEPTION '% item(s) have a non-zero total_gross_earnings. The premise is that '
            'nothing ever computed it — one of them did, and it must be understood before the '
            'column goes.', v_nonzero;
    END IF;

    ALTER TABLE payroll_run_items DROP COLUMN total_gross_earnings;
    RAISE NOTICE 'total_gross_earnings dropped; the payslip shows total_net_earnings, which is '
        'the figure that is actually computed.';
END $$;
