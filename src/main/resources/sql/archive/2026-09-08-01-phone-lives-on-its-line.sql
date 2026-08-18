-- =============================================================================
-- The current month's phone lives on its line
-- =============================================================================
-- WHAT current_month_telephone WAS
-- The authoritative store for the phone charge, with the PHONE_CURRENT_MONTH
-- line kept in step beside it — but only when somebody edited the phone through
-- the screen. The line was created for every item by the component backfill and
-- left at zero, so any phone entered BEFORE that backfill exists in the column
-- and nowhere else.
--
-- Three unarchived items are in exactly that state: 1217 (2024-04), 1342
-- (2024-04) and 1479 (2024-08), holding 3200, 5000 and 6000 with a zero line and
-- has_manual_input false. This copies the column onto the line for them before
-- the column goes, or the figures would be lost.
--
-- WHAT THIS DOES NOT DECIDE
-- OPEN-12 is untouched: whether the CURRENT month's phone should reduce the
-- current month's pay is a business question, still unanswered, and nothing here
-- answers it. PHONE_CURRENT_MONTH reaches no total before this migration and
-- reaches none after it. What changes is only where the figure is kept — and
-- therefore that next month's PHONE_PREVIOUS_MONTH is now raised from the line,
-- which is what the screen writes.
--
-- WHY IT IS SAFE TO COPY
-- The three lines have has_manual_input = false and amount = 0: nobody has
-- entered anything on them, so there is no human figure to overwrite. Where a
-- line DOES hold a different human figure, the two disagree about something
-- somebody decided, and a script must not pick a winner — the migration refuses.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_conflict INTEGER;
    v_copied   INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'payroll_run_items'
                     AND column_name = 'current_month_telephone') THEN
        RAISE NOTICE 'current_month_telephone already dropped; the phone is on its line.';
        RETURN;
    END IF;

    -- A line somebody has touched, holding something other than the column.
    SELECT count(*) INTO v_conflict
    FROM payroll_run_items i
    JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
    JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
    WHERE i.archived_at IS NULL
      AND c.code = 'PHONE_CURRENT_MONTH'
      AND COALESCE(i.current_month_telephone, 0) <> COALESCE(a.amount, 0)
      AND (a.has_manual_input OR COALESCE(a.amount, 0) <> 0);

    IF v_conflict > 0 THEN
        RAISE EXCEPTION 'Refusing to settle: % phone line(s) hold a figure somebody entered that '
            'differs from the column. Which of the two is right is not a script''s decision.',
            v_conflict;
    END IF;

    UPDATE payroll_adjustments a
    SET amount = i.current_month_telephone,
        system_amount = i.current_month_telephone,
        updated_at = now()
    FROM payroll_run_items i,
         payroll_adjustment_categories c
    WHERE a.payroll_run_item_id = i.id
      AND c.id = a.payroll_adjustment_category_id
      AND c.code = 'PHONE_CURRENT_MONTH'
      AND i.archived_at IS NULL
      AND COALESCE(i.current_month_telephone, 0) <> COALESCE(a.amount, 0);
    GET DIAGNOSTICS v_copied = ROW_COUNT;

    RAISE NOTICE '% phone line(s) settled from the column they were written beside.', v_copied;
END $$;

ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS current_month_telephone;

DO $$
BEGIN
    RAISE NOTICE 'payroll_run_items is down to % columns.',
        (SELECT count(*) FROM information_schema.columns WHERE table_name = 'payroll_run_items');
END $$;
