-- =============================================================================
-- The nine bonus mirror columns go
-- =============================================================================
-- These held the monthly bonus's base, its correction and their total, each with
-- a _system and an _overridden companion. The MONTHLY_BONUS line has been the
-- source since step 2; the columns were kept written so the two could be
-- compared before anything was dropped.
--
-- WHY THEY OUTLIVED THE MEAL AND TRANSPORT COLUMNS BY TWO MIGRATIONS
-- The panel offers the base and the additional bonus as two independently
-- editable figures, and a line has ONE editable input plus a correction. Editing
-- the base at line level had to be expressed as a typed total — and a typed
-- total, by definition, has no parts, so the next recalculation would have
-- collapsed the split the panel shows.
--
-- WHAT RESOLVED IT
-- AdjustmentPatchDto gained `baseAmount`: the server adds it to the correction
-- already on the line and writes the sum into `amount`, WITHOUT setting
-- is_overridden and without demanding a reason, because the formula still runs.
-- correctionAmount now moves the total with it instead of taking the difference
-- out of the base. The two parts are editable, independently, and survive a
-- recalculation: the effective base is amount - correction_amount, and the
-- rules' own figures stay beside them as system_amount and
-- system_correction_amount.
--
-- WHAT ALREADY STOPPED USING THE COLUMNS
--   * every screen and the payslip PDF read the line (payrollFigures.ts)
--   * the parameters panel edits the line, through the adjustments array —
--     saveBonusPart sends baseAmount and correctionAmount
--   * PayrollRunItemPatchRequest no longer has baseBonusAmount,
--     bonusCorrectionAmount or totalBonusAmount, and patch() has no bonus branch
--   * recalculate() writes the parts to the line only; PayrollRunItemResponse no
--     longer emits them and PayrollRunItem no longer maps them
--
-- A RESET IS A RE-SEND OF THE SYSTEM FIGURE, not clearOverride. The two parts
-- share one line, so clearing the line would throw the other part away — undoing
-- a base edit would silently drop a correction somebody typed. Re-sending the
-- rules' figure is a true reset: the recalculation keeps a part only while it
-- differs from the system's.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_drift INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'payroll_run_items' AND column_name = 'total_bonus_amount') THEN
        RAISE NOTICE 'Bonus mirror columns already dropped; nothing to do.';
        RETURN;
    END IF;

    -- The column and the line must agree before the column is thrown away. Same
    -- check payroll-step3-column-vs-line.sql makes, narrowed to the bonus and
    -- run here so the drop cannot happen while they disagree.
    SELECT count(*) INTO v_drift
    FROM payroll_run_items i
    JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
    JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
    WHERE i.archived_at IS NULL
      AND c.code = 'MONTHLY_BONUS'
      AND a.calculated_at IS NOT NULL
      AND (COALESCE(i.total_bonus_amount, 0) <> COALESCE(a.amount, 0)
        OR COALESCE(i.bonus_correction_amount, 0) <> COALESCE(a.correction_amount, 0));

    IF v_drift > 0 THEN
        RAISE EXCEPTION 'Refusing to drop: % calculated bonus line(s) disagree with their item '
            'columns. Run docs/business-rules/payroll-step3-column-vs-line.sql and settle the '
            'difference first — dropping now would destroy whichever of the two is right.', v_drift;
    END IF;
END $$;

ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS base_bonus_amount_system,
    DROP COLUMN IF EXISTS base_bonus_amount,
    DROP COLUMN IF EXISTS base_bonus_amount_overridden,
    DROP COLUMN IF EXISTS bonus_correction_amount_system,
    DROP COLUMN IF EXISTS bonus_correction_amount,
    DROP COLUMN IF EXISTS bonus_correction_amount_overridden,
    DROP COLUMN IF EXISTS total_bonus_amount_system,
    DROP COLUMN IF EXISTS total_bonus_amount,
    DROP COLUMN IF EXISTS total_bonus_amount_overridden;

DO $$
BEGIN
    RAISE NOTICE 'payroll_run_items is down to % columns. The bonus lives only on its line now, '
        'both parts of it.',
        (SELECT count(*) FROM information_schema.columns WHERE table_name = 'payroll_run_items');
END $$;
