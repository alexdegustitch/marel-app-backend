-- =============================================================================
-- Audit for the payroll tables  (prerequisite for D7)
-- =============================================================================
-- D7 says: do not build a payroll_component_override_history table, use the
-- existing audit_logs. Diagnostic Q10b (2026-07-31) found that decision resting
-- on nothing — audit_tables has 37 rows and payroll_adjustments is not one of
-- them. There is no trigger and there is no history. So D7 is not true today, and
-- this migration is what makes it true.
--
-- WHAT IS AUDITED, AND WHAT DELIBERATELY IS NOT
--
--   payroll_adjustments             YES — this is the table D7 actually needs.
--                                   Overrides, corrections and reasons live here.
--   payroll_adjustment_categories   YES — the catalogue. Low write volume, and a
--                                   change to section_code or impact_code moves
--                                   money for everyone at once.
--
--   payroll_run_items               NO, on purpose. getForPayrollAccess rewrites
--                                   an item on every lazy recalculation — a read
--                                   of a stale item is a write — so auditing it
--                                   row by row records mostly system churn and
--                                   would bury the human decisions in it. The
--                                   amounts on an item are all derived from the
--                                   adjustments and categories that ARE audited.
--                                   Revisit with a measurement, not a guess.
--
--                                   AMENDED 2026-08-26: that last sentence was
--                                   wrong about two columns. manual_adjusted_minutes
--                                   and hourly_rate_overridden are typed by a
--                                   person and derived from nothing, so they were
--                                   audited nowhere at all. 2026-08-26-01 adds a
--                                   trigger for exactly those two, with a WHEN
--                                   clause so the churn objection above still
--                                   holds. The rest of the row remains unaudited.
--
-- Re-runnable: guarded INSERT, triggers dropped before being created.
-- =============================================================================

INSERT INTO audit_tables (table_name)
SELECT v.table_name
FROM (VALUES ('payroll_adjustments'),
             ('payroll_adjustment_categories')) AS v(table_name)
WHERE NOT EXISTS (
    SELECT 1 FROM audit_tables t WHERE t.table_name = v.table_name
);

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_adjustments ON payroll_adjustments;
CREATE TRIGGER trg_audit_logs_payroll_adjustments
    AFTER INSERT OR UPDATE OR DELETE ON payroll_adjustments
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_adjustment_categories ON payroll_adjustment_categories;
CREATE TRIGGER trg_audit_logs_payroll_adjustment_categories
    AFTER INSERT OR UPDATE OR DELETE ON payroll_adjustment_categories
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

-- The trigger resolves table_id by name and fails on a NOT NULL violation if the
-- row is missing, which would break every write to these tables rather than just
-- the audit. Better to find out here.
DO $$
DECLARE
    v_missing TEXT;
BEGIN
    SELECT string_agg(v.table_name, ', ')
      INTO v_missing
    FROM (VALUES ('payroll_adjustments'),
                 ('payroll_adjustment_categories')) AS v(table_name)
    WHERE NOT EXISTS (SELECT 1 FROM audit_tables t WHERE t.table_name = v.table_name);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'audit_tables row missing for: %. The trigger would fail every '
            'write to those tables.', v_missing;
    END IF;

    RAISE NOTICE 'Payroll adjustment audit is live. payroll_run_items is intentionally '
        'not audited — see the header.';
END $$;
