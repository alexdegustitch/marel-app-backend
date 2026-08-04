-- =============================================================================
-- Settle the overrides that predate the rule, and finish validating it
-- =============================================================================
-- 2026-08-25-01 added chk_pa_override_reason as NOT VALID so that overrides
-- recorded before the rule existed were not given invented explanations. That was
-- right about the explanations and wrong about the consequences: NOT VALID
-- exempts a row from the INITIAL check, never from being checked when something
-- UPDATES it. The 24 rows sat quietly until a recalculation touched one, and then
-- the recalculation failed outright — 12 payroll items could not be recalculated
-- at all.
--
-- PayrollRunItemService now clears such a flag as it writes over the amount, so
-- they would settle themselves one at a time as each item is next recalculated.
-- This does it in one pass instead of leaving a dozen employees' payroll waiting
-- for a defect to be stepped on.
--
-- WHAT THIS DOES NOT DO
-- It does not touch a single amount. is_overridden feeds no sum — the only code
-- that reads it is the guard above and the display DTOs — so nobody's pay moves.
-- It does not touch an override that HAS a reason: a reason is what separates a
-- decision somebody made from a flag nobody can explain.
--
-- WHAT IS PRESERVED
-- has_manual_input is set on every row cleared. The flag being dropped is the
-- only record that a person put that figure there, and "somebody entered this"
-- is exactly what has_manual_input means. Losing it would turn a typed figure
-- into one the system looks to have produced.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_rows      INTEGER;
    v_remaining INTEGER;
BEGIN
    UPDATE payroll_adjustments
    SET has_manual_input = TRUE,
        is_overridden    = FALSE
    WHERE is_overridden = TRUE
      AND (override_reason IS NULL OR length(trim(override_reason)) = 0);
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    RAISE NOTICE '% legacy override(s) settled: flag cleared, has_manual_input set so the '
        'fact that a person entered the figure is not lost. No amount was changed.', v_rows;

    -- With none left, the constraint can finally be validated: from here it holds
    -- for every row, not only for new writes. 2026-08-25-01 said to do this
    -- "once they are dealt with" — this is that moment.
    SELECT count(*) INTO v_remaining
    FROM payroll_adjustments
    WHERE is_overridden = TRUE
      AND (override_reason IS NULL OR length(trim(override_reason)) = 0);

    IF v_remaining = 0 THEN
        ALTER TABLE payroll_adjustments VALIDATE CONSTRAINT chk_pa_override_reason;
        RAISE NOTICE 'chk_pa_override_reason is now VALIDATED — it holds for every existing '
            'row, not just for new writes.';
    ELSE
        RAISE NOTICE '% row(s) still unreasoned; constraint left NOT VALID.', v_remaining;
    END IF;
END $$;

COMMENT ON CONSTRAINT chk_pa_override_reason ON payroll_adjustments IS
    'A typed-in total must carry a reason. Validated 2026-08-30 once the rows predating the rule were settled by 2026-08-30-01 — they kept their amounts and gained has_manual_input, rather than being given an invented explanation.';
