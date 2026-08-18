-- =============================================================================
-- The adjustment audit records decisions, not the calculation's own work
-- =============================================================================
-- WHAT WAS WRONG
-- trg_audit_logs_payroll_adjustments was a plain AFTER INSERT OR UPDATE OR
-- DELETE with no WHEN clause, so EVERY write left a full-row diff per line. A
-- recalculation rewrites system_amount, system_quantity, system_unit_amount,
-- calculation_inputs and calculated_at on every line of an item, and a read of a
-- stale item IS a write — getForPayrollAccess recomputes inside the reader's own
-- request. So opening a payroll wrote about ten audit rows per item, for nothing
-- anybody decided.
--
-- In the development database: 33 472 update entries, of which 20 954 touch
-- nothing but system_*, calculated_at and calculation_inputs. Real human
-- decisions number around thirty. A dispute six months from now means finding
-- those thirty in thirty thousand — which is the same as having no trail, except
-- that it looks like one.
--
-- WHY NOT A WHEN CLAUSE OVER THE COLUMNS
-- Because a patch and the recalculation it triggers land in the SAME UPDATE on
-- the same row: the service edits the entity, recalculates, and Hibernate flushes
-- once at commit. No column test separates "the administrator typed 1 000" from
-- "the calculation rewrote the line on the way past" — 2026-09-03-01 wrote this
-- down for the activity trigger and it is just as true here. A clause narrow
-- enough to drop the churn would drop the decision with it, and a lost decision
-- is far worse than a thousand spurious rows.
--
-- WHAT SEPARATES THEM IS WHICH METHOD WAS CALLED, and only the caller knows.
-- PayrollRunItemService.markHumanDecision — called by patch, lock and unlock, and
-- by nothing else — sets app.records_decision for the rest of the transaction.
-- This trigger fires only when it is set. Reading a payroll, including the
-- recalculation that a read may trigger, sets nothing and records nothing.
--
-- WHAT IS STILL RECORDED, deliberately: the WHOLE diff of a decision's
-- transaction, including lines the recalculation rewrote as a consequence. The
-- consequence of a decision is the interesting part of it — an entry shows the
-- meal price somebody typed together with every amount that moved because of it.
--
-- THE EXISTING 33 472 ROWS ARE LEFT ALONE. Most are noise, but a real decision
-- and a recalculation wrote indistinguishable rows, so deleting the noise would
-- take true entries with it. They cost a little space and nothing else; from
-- today the table only grows when somebody decides something.
--
-- Re-runnable.
-- =============================================================================

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_adjustments ON payroll_adjustments;

CREATE TRIGGER trg_audit_logs_payroll_adjustments
    AFTER INSERT OR UPDATE OR DELETE ON payroll_adjustments
    FOR EACH ROW
    WHEN (COALESCE(current_setting('app.records_decision', true), '') = 'true')
    EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TRIGGER trg_audit_logs_payroll_adjustments ON payroll_adjustments IS
    'Fires only inside a transaction that PayrollRunItemService has marked as somebody''s decision (patch, lock, unlock). A recalculation — including the one a read of a stale item triggers — records nothing. Without this the table grew by roughly ten rows per item every time anybody opened a payroll.';

-- audit_trigger_fn is NOT touched: 48 tables share it, and the rule being added
-- here is about this table only.

DO $$
DECLARE
    v_total INTEGER;
    v_churn INTEGER;
BEGIN
    SELECT count(*) INTO v_total
    FROM audit_logs l
    JOIN audit_tables t ON t.id = l.table_id
    JOIN audit_actions a ON a.id = l.action_id
    WHERE t.table_name = 'payroll_adjustments' AND a.action_name = 'update';

    -- How many of those touched nothing a person can enter. Reported so the size
    -- of what this stops is on the record, next to the change that stops it.
    WITH keys AS (
        SELECT l.id, jsonb_object_keys(l.changes) AS k
        FROM audit_logs l
        JOIN audit_tables t ON t.id = l.table_id
        JOIN audit_actions a ON a.id = l.action_id
        WHERE t.table_name = 'payroll_adjustments' AND a.action_name = 'update'
    )
    SELECT count(*) INTO v_churn
    FROM (SELECT id FROM keys
          GROUP BY id
          HAVING bool_and(k IN ('calculated_at', 'calculation_inputs', 'system_amount',
                                'system_quantity', 'system_unit_amount',
                                'system_correction_amount', 'updated_at', 'status'))) pure;

    RAISE NOTICE 'Adjustment audit now fires only on a decision. % of % existing update entries '
        'are pure recalculation; they are LEFT AS THEY ARE, because a decision and a '
        'recalculation wrote indistinguishable rows and the noise cannot be deleted without the '
        'signal. From today the table only grows when somebody decides something.',
        v_churn, v_total;
END $$;
