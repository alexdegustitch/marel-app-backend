-- =============================================================================
-- The payslip gets the note it says it is printing
-- =============================================================================
-- WHAT CHANGES
--   payroll_run_items.director_note — one new nullable text column, and one more
--   condition on the partial audit trigger.
--
-- WHY AT ALL
--   The payslip has printed a block headed "Napomena Direktora:" since it was
--   written. What it fills that block with is employees.notes — the standing
--   general note on the worker's record, which is not the director's, is not
--   about this month, and is written and read by everybody who can open an
--   employee. A heading that names one thing and prints another is worse than
--   no heading: the reader believes the wrong person wrote it.
--
--   So the note the payslip claims to print gets somewhere to live.
--
-- WHY ON THE ITEM AND NOT ON THE EMPLOYEE
--   A payslip is a document about ONE MONTH. A note that stood on the employee
--   would print unchanged on every payslip until somebody remembered to clear
--   it — and the month it was actually written about would be unrecoverable.
--   On the item, each month's note is that month's, and the history is the
--   history of the payslips that were handed over.
--
--   employees.notes is left exactly as it is. It is a real thing that real
--   people use; it simply stops being printed under somebody else's name.
--
-- WHY TEXT AND NOT VARCHAR(n)
--   It is a paragraph a director types, held as the same rich-text HTML that
--   payroll_run_items.note already holds. A length limit here would be
--   discovered by losing the end of somebody's sentence.
--
-- WHO SEES IT
--   Enforced in the service, not here: PAYROLL_DIRECTOR_NOTE, held by
--   administrators alone. The column has no opinion about that, and putting the
--   rule in the schema would mean two places for it to drift.
--
--   Note that this is narrower on SCREEN than on paper, deliberately. The
--   employee reading their own payroll in the application never receives the
--   field; the payslip that carries it is the one the administration renders and
--   hands over. That is already how the block behaved — MyPayrollsSection has
--   always passed null for it.
--
-- WHY IT JOINS THE AUDIT TRIGGER
--   trg_audit_logs_payroll_run_items_human_input records the values a PERSON
--   enters, because everything else on this table is rewritten by every lazy
--   recalculation. A sentence that goes onto somebody's payslip over the
--   director's name is exactly that kind of value.
--
-- MIGRATION IMPACT
--   Additive. One nullable column with no default, so every existing row is
--   valid and unchanged, and one trigger recreated with a strictly WIDER
--   condition. No payroll figure moves and nothing outside payroll_run_items is
--   touched. Reversible by dropping the column and restoring the trigger's
--   previous four conditions.
-- =============================================================================

ALTER TABLE payroll_run_items
    ADD COLUMN director_note text;

COMMENT ON COLUMN payroll_run_items.director_note IS
    'The note printed on THIS MONTH''S payslip under "Napomena Direktora". Rich '
    'text, like payroll_run_items.note. Visible in the application only to '
    'PAYROLL_DIRECTOR_NOTE holders; distinct from employees.notes, which is the '
    'worker''s standing general note and is no longer printed.';


-- Recreated rather than added to: a trigger's WHEN clause cannot be altered in
-- place. Strictly wider — every update that fired it still fires it.
DROP TRIGGER IF EXISTS trg_audit_logs_payroll_run_items_human_input ON payroll_run_items;

CREATE TRIGGER trg_audit_logs_payroll_run_items_human_input
    AFTER UPDATE ON payroll_run_items
    FOR EACH ROW
    WHEN (OLD.hourly_rate_overridden     IS DISTINCT FROM NEW.hourly_rate_overridden
       OR OLD.hourly_rate_manual         IS DISTINCT FROM NEW.hourly_rate_manual
       OR OLD.performance_mark           IS DISTINCT FROM NEW.performance_mark
       OR OLD.performance_mark_applied   IS DISTINCT FROM NEW.performance_mark_applied
       OR OLD.director_note              IS DISTINCT FROM NEW.director_note)
    EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TRIGGER trg_audit_logs_payroll_run_items_human_input ON payroll_run_items IS
    'PARTIAL audit. Fires only when a value a PERSON enters actually changes — the '
    'typed hourly rate, the flag that records it, the performance mark, whether that '
    'mark is in force, and the director''s note on the payslip. Everything else on '
    'the item is recalculated constantly and is auditable through payroll_adjustments. '
    'Do not read the payroll_run_items row in audit_tables as full coverage.';
