-- =============================================================================
-- The manual time correction is its rows, not a column beside them
-- =============================================================================
-- WHAT manual_adjusted_minutes WAS
-- One integer holding the whole correction to somebody's paid time. 2026-08-27-01
-- replaced it with payroll_time_adjustments: one row per cause, each with its own
-- reason, its own applied/archived state and its own audit trail. The column was
-- then written from those rows on every save, so that nothing reading it had to
-- change at once.
--
-- The two never disagreed — 0 drift across 849 unarchived items, 2 of which carry
-- a correction at all — but nothing except every writer remembering made them
-- agree. That is the same double bookkeeping the meal, transport, bonus and phone
-- mirrors were dropped to end, and the stakes here are somebody's paid hours.
--
-- WHAT REPLACES IT
-- A @Transient property on the entity, filled by the service from the rows —
-- singly on the item endpoints, and in ONE batched query for lists, because a
-- 300-person payroll screen must not become 300 queries. The API field is
-- unchanged: the screen and the payslip read manualAdjustedMinutes exactly as
-- before. total_payroll_minutes is NOT touched — it is a stored figure the
-- calculation produces, not a mirror of anything.
--
-- THE AUDIT TRIGGER HAD TO MOVE FIRST
-- trg_audit_logs_payroll_run_items_human_input names this column in its WHEN
-- clause, so the column cannot be dropped while it stands. Its minutes half is
-- redundant anyway: payroll_time_adjustments carries trg_audit_logs_payroll_time_-
-- adjustments, which records the correction itself — who, when, from what, and
-- with the reason the row is required to have. The trigger is recreated below
-- watching hourly_rate_overridden alone.
--
-- WHAT IS LOST: nothing that was not recorded twice. What is gained: a correction
-- can no longer exist in one place and not the other.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_drift INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'payroll_run_items'
                     AND column_name = 'manual_adjusted_minutes') THEN
        RAISE NOTICE 'manual_adjusted_minutes already dropped; the correction is its rows.';
        RETURN;
    END IF;

    -- The column against the rows it was written from. A difference means one of
    -- the two is wrong about how long somebody worked, and that is not something
    -- to discover after the column is gone.
    SELECT count(*) INTO v_drift
    FROM payroll_run_items i
    WHERE i.archived_at IS NULL
      AND COALESCE(i.manual_adjusted_minutes, 0) <> COALESCE((
            SELECT SUM(t.minutes)
            FROM payroll_time_adjustments t
            JOIN payroll_time_adjustment_categories tc ON tc.id = t.payroll_time_adjustment_category_id
            WHERE t.payroll_run_item_id = i.id
              AND t.is_applied
              AND t.archived_at IS NULL
              AND tc.impact_code = 'PAYABLE_MINUTES'), 0);

    IF v_drift > 0 THEN
        RAISE EXCEPTION 'Refusing to drop: % item(s) whose manual_adjusted_minutes does not match '
            'their payroll_time_adjustments rows. Settle which is right before the column goes.',
            v_drift;
    END IF;
END $$;

-- The trigger, without the half that is now recorded on its own table.
DROP TRIGGER IF EXISTS trg_audit_logs_payroll_run_items_human_input ON payroll_run_items;
CREATE TRIGGER trg_audit_logs_payroll_run_items_human_input
    AFTER UPDATE ON payroll_run_items
    FOR EACH ROW
    WHEN (OLD.hourly_rate_overridden IS DISTINCT FROM NEW.hourly_rate_overridden)
    EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TRIGGER trg_audit_logs_payroll_run_items_human_input ON payroll_run_items IS
    'PARTIAL audit. Fires only when hourly_rate_overridden actually changes — the one value left on this table that a person enters rather than the calculation derives. The manual time correction moved to payroll_time_adjustments, which is audited on its own table. Everything else on the item is recalculated constantly and is auditable through payroll_adjustments. Do not read the payroll_run_items row in audit_tables as full coverage.';

ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS manual_adjusted_minutes;

DO $$
BEGIN
    RAISE NOTICE 'payroll_run_items is down to % columns. The time correction lives in '
        'payroll_time_adjustments, where it has a reason and an audit trail.',
        (SELECT count(*) FROM information_schema.columns WHERE table_name = 'payroll_run_items');
END $$;
