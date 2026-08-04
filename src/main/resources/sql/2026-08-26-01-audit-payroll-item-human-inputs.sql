-- =============================================================================
-- Audit the HUMAN INPUTS on payroll_run_items — and only those
-- =============================================================================
-- WHAT THIS CLOSES
-- 2026-08-05-02 left payroll_run_items unaudited, and justified it like this:
--
--     "The amounts on an item are all derived from the adjustments and
--      categories that ARE audited."
--
-- That is true of the money and false of manual_adjusted_minutes. Those minutes
-- are not derived from anything: an administrator types them, they change what
-- the employee is paid through total_payroll_minutes, and they exist on no other
-- table. Until now, adding 60 minutes to somebody's month left no record of who
-- did it, when, or from what. Same for hourly_rate_overridden, which is how "this
-- person's rate was set by hand" is stored.
--
-- WHY NOT AUDIT THE WHOLE ROW
-- The original objection still holds. getForPayrollAccess rewrites an item on
-- every lazy recalculation — a read of a stale item is a write — so a row-level
-- audit would record mostly system churn and bury the human decisions in it. The
-- point here is the decision, not the arithmetic that follows it.
--
-- WHY A WHEN CLAUSE AND NOT "AFTER UPDATE OF col"
-- UPDATE OF fires when a column appears in the statement's target list, not when
-- its value changes. Hibernate names every column on every save, so UPDATE OF
-- would fire on each recalculation and we would be back to the churn. WHEN
-- compares the values themselves and is indifferent to how the UPDATE was built.
--
-- WHAT LANDS IN audit_logs.changes
-- The WHEN clause decides WHETHER to record; audit_trigger_fn then diffs the
-- WHOLE row. So an entry shows the minutes that were typed together with every
-- amount that moved because of it. That is deliberate — the consequence of the
-- decision is the interesting part — but it means these rows are not a
-- two-column log.
--
-- Re-runnable.
-- =============================================================================

-- Required for audit_trigger_fn to resolve TG_TABLE_NAME to an id. It does NOT
-- by itself audit anything: triggers are attached explicitly, one at a time. The
-- presence of this row must not be read as "payroll_run_items is audited" — only
-- the two columns named in the trigger below are.
INSERT INTO audit_tables (table_name)
SELECT 'payroll_run_items'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'payroll_run_items');

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_run_items_human_input ON payroll_run_items;
CREATE TRIGGER trg_audit_logs_payroll_run_items_human_input
    AFTER UPDATE ON payroll_run_items
    FOR EACH ROW
    WHEN (OLD.manual_adjusted_minutes IS DISTINCT FROM NEW.manual_adjusted_minutes
       OR OLD.hourly_rate_overridden   IS DISTINCT FROM NEW.hourly_rate_overridden)
    EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TRIGGER trg_audit_logs_payroll_run_items_human_input ON payroll_run_items IS
    'PARTIAL audit. Fires only when manual_adjusted_minutes or hourly_rate_overridden actually change — the two values on this table a person enters rather than the calculation derives. Everything else on the item is recalculated constantly and is auditable through payroll_adjustments. Do not read the payroll_run_items row in audit_tables as full coverage.';

-- No INSERT trigger: an insert here is the run initialising an item with
-- defaults, which is not a decision anybody made. No DELETE trigger: items are
-- archived, never deleted.

DO $$
DECLARE
    v_manual INTEGER;
    v_rate   INTEGER;
BEGIN
    SELECT count(*) INTO v_manual FROM payroll_run_items WHERE manual_adjusted_minutes <> 0;
    SELECT count(*) INTO v_rate   FROM payroll_run_items WHERE hourly_rate_overridden;

    RAISE NOTICE 'Partial audit live on payroll_run_items. % item(s) already carry manual minutes and % an overridden rate; those PAST decisions have no trail and cannot get one — the trigger records changes from now on.',
        v_manual, v_rate;
END $$;
