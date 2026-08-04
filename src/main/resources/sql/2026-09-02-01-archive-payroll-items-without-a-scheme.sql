-- =============================================================================
-- Archive payroll items for months the employee was not yet employed
-- =============================================================================
-- WHAT THESE ARE
-- The step-3 maintenance sweep could not recalculate 231 of 1084 items, all with
-- the same reason: "Zaposleni N nema način obračuna za period ...". D1 makes zero
-- compensation schemes an error rather than a silent default, so the calculation
-- refuses — correctly.
--
-- The data is what is wrong. Employee 17 is employed from 2024-07-25, has a
-- STANDARD scheme from the same day, and carries a payroll item for 2023-01-01 —
-- eighteen months before they were hired. 235 items across five periods
-- (2023-01, 2024-04, 2024-08, 2024-09, 2026-01) are like this.
--
-- WHY ARCHIVING AND NOT DELETING
-- Everything reads through archived_at IS NULL, so archiving takes them out of
-- every query, every total and the sweep, while the rows stay recoverable. This
-- project keeps history; a payroll row is not something to delete because it is
-- inconvenient.
--
-- WHY THIS IS SAFE
-- Verified before writing, and enforced again below: all 235 are DRAFT, none is
-- LOCKED, none has a single worked minute, and none carries any money in any of
-- the seven amount columns. The migration REFUSES to run if that stops being
-- true — an item with money in it is not an artifact and must not be archived by
-- a script.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_total    INTEGER;
    v_money    INTEGER;
    v_minutes  INTEGER;
    v_locked   INTEGER;
    v_archived INTEGER;
BEGIN
    CREATE TEMP TABLE candidates ON COMMIT DROP AS
    SELECT i.id
    FROM payroll_run_items i
    WHERE i.archived_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM employee_compensation_scheme_history h
          WHERE h.employee_id = i.employee_id
            AND h.archived_at IS NULL
            AND h.valid_from <= i.period
            AND (h.valid_until IS NULL OR h.valid_until >= i.period));

    SELECT count(*) INTO v_total FROM candidates;
    IF v_total = 0 THEN
        RAISE NOTICE 'No payroll items without a compensation scheme; nothing to archive.';
        RETURN;
    END IF;

    SELECT count(*) INTO v_locked
    FROM payroll_run_items i JOIN candidates c ON c.id = i.id
    WHERE i.status = 'LOCKED';

    SELECT count(*) INTO v_minutes
    FROM payroll_run_items i JOIN candidates c ON c.id = i.id
    WHERE COALESCE(i.total_work_minutes, 0) <> 0;

    -- THE MONEY CHECK IS ASSEMBLED FROM THE COLUMNS THAT STILL EXIST.
    --
    -- Named directly, this broke re-runnability the moment a later migration
    -- dropped one of them: total_meal_allowance_amount, total_transport_-
    -- allowance_amount and total_bonus_amount are all gone by 2026-09-06, and a
    -- plain reference to a dropped column fails at execution even inside a
    -- branch that would not have mattered. The item's own money lines are
    -- checked below either way, so nothing is skipped when a column has gone —
    -- an amount on a line is what "carries money" actually means.
    SELECT count(*) INTO v_money
    FROM payroll_run_items i JOIN candidates c ON c.id = i.id
    WHERE COALESCE(i.total_net_earnings, 0) <> 0
       OR COALESCE(i.net_payable_amount, 0) <> 0
       OR COALESCE(i.previously_paid_amount, 0) <> 0
       OR COALESCE(i.current_balance_amount, 0) <> 0
       OR EXISTS (SELECT 1 FROM payroll_adjustments a
                  WHERE a.payroll_run_item_id = i.id
                    AND COALESCE(a.amount, 0) <> 0);

    IF v_locked > 0 OR v_minutes > 0 OR v_money > 0 THEN
        RAISE EXCEPTION 'Refusing to archive: % locked, % with worked minutes, % carrying money. '
            'The premise is that these are empty items for months before employment. '
            'One of them is not, and a script must not archive it.',
            v_locked, v_minutes, v_money;
    END IF;

    UPDATE payroll_run_items i
    SET archived_at = now()
    FROM candidates c
    WHERE c.id = i.id;
    GET DIAGNOSTICS v_archived = ROW_COUNT;

    RAISE NOTICE '% payroll item(s) archived: no compensation scheme for their period, and '
        'every one empty — DRAFT, no worked minutes, no money. They stay in the table; '
        'archived_at takes them out of every read.', v_archived;
END $$;
