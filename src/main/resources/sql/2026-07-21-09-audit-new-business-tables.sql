-- =============================================================================
-- Audit coverage for the new business tables
-- =============================================================================
-- The existing audit mechanism is reused unchanged: register the table name in
-- audit_tables, attach audit_trigger_fn, and let AuditUserAspect supply the
-- actor through set_config('app.user_id', ...). No second audit framework is
-- introduced.
--
-- Run this AFTER the other 2026-07-21-* scripts — the tables must exist.
-- (product_manufacturing_times and manufacturing_time_requests are registered by
-- 2026-07-21-manufacturing-time-requests.sql, next to the change that made them
-- necessary.)
--
-- WHAT IS DELIBERATELY NOT AUDITED, and why:
--   user_notifications      every read/dismiss would write an audit row; the
--                           row's own read_at/dismissed_at already record it
--   notification_deliveries worker retry churn, several writes per delivery
--   outbox_events           same, plus the payload is already an event record
--   user_sessions           one write per heartbeat per user per minute
--   user_preferences,
--   user_table_preferences,
--   user_saved_views        personal display settings with no business or
--                           payroll consequence; auditing them adds noise, not
--                           accountability
-- Business actor columns (reviewed_by, processed_by, added_by, removed_by,
-- revoked_by) remain part of current state and are NOT a substitute for, nor a
-- duplicate of, the audit history: the columns say who owns the state now, the
-- audit log says how it got there.
-- =============================================================================

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'user_registration_requests',
        'mailing_lists',
        'mailing_list_members',
        'production_order_mailing_lists',
        'production_order_recipients',
        'notification_events'
    ]
    LOOP
        INSERT INTO audit_tables (table_name)
        SELECT t
        WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = t);

        EXECUTE format('DROP TRIGGER IF EXISTS trg_audit_logs_%1$s ON %1$I', t);
        EXECUTE format(
            'CREATE TRIGGER trg_audit_logs_%1$s
                 AFTER INSERT OR UPDATE OR DELETE ON %1$I
                 FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn()', t);
    END LOOP;
END $$;
