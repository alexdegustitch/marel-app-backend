-- =============================================================================
-- One adjustment row per category per payroll item  (D9)
-- =============================================================================
-- Diagnostic Q1 on 2026-07-31 found zero duplicates across all 12 337 rows, so no
-- data clean-up is needed. The guard below runs anyway: this migration executes
-- against whatever the database looks like on the day it is applied, not the day
-- it was written, and a duplicate created in between must stop it rather than be
-- silently merged.
--
-- If it does fire, do NOT sum or delete the rows to make it pass. Q1b reports the
-- creation gap for each pair — seconds apart means a double POST, days apart means
-- somebody used it deliberately and the constraint is the wrong answer.
-- =============================================================================

DO $$
DECLARE
    v_duplicates INTEGER;
BEGIN
    SELECT count(*) INTO v_duplicates
    FROM (
        SELECT payroll_run_item_id, payroll_adjustment_category_id
        FROM payroll_adjustments
        GROUP BY 1, 2
        HAVING count(*) > 1
    ) d;

    IF v_duplicates > 0 THEN
        RAISE EXCEPTION 'payroll_adjustments has % duplicated (item, category) pair(s). '
            'Run Q1/Q1b from payroll-migration-diagnostics.sql and resolve each one '
            'deliberately before adding the constraint.', v_duplicates;
    END IF;
END $$;

ALTER TABLE payroll_adjustments
    DROP CONSTRAINT IF EXISTS uq_payroll_adjustment_item_category;
ALTER TABLE payroll_adjustments
    ADD CONSTRAINT uq_payroll_adjustment_item_category
    UNIQUE (payroll_run_item_id, payroll_adjustment_category_id);

COMMENT ON CONSTRAINT uq_payroll_adjustment_item_category ON payroll_adjustments IS
    'One row per category per item. There is no second "Ostalo" line: a category is a labelled slot, not a ledger of entries.';
