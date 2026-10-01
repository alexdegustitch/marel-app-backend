-- =============================================================================
-- A payroll mark is picked, not typed
-- =============================================================================
-- WHAT CHANGES
--   payroll_run_items.performance_mark_id — the ocena on a month's payroll is
--   now CHOSEN from the performance_marks šifarnik (V57) instead of typed as a
--   bare 0–2 multiplier. The chosen row is a dated VERSION, so "what was ocena
--   3 worth when this month was calculated" reads straight off the reference;
--   the šifarnik's close-and-open versioning (never overwriting amounts) is
--   what makes the reference audit-proof.
--
-- WHAT THE REFERENCED ROW DOES TO THE RATE
--   The old mark MULTIPLIED the base rate (base × 0.90). The šifarnik row
--   ADJUSTS it instead: amount_unit PERCENT means base × (1 + amount/100),
--   RSD_PER_HOUR means base + amount — the sign carries the direction. The
--   derivation stays in PayrollRunItem.effectiveHourlyRate(), the apply /
--   revert two-person flow is untouched, and the result is floored at zero:
--   V57 left "may a deduction drag the rate below zero" open, and the answer
--   here is no — the legacy multiplier could already reach exactly 0 (mark 0)
--   and no lower.
--
-- WHAT HAPPENS TO THE OLD COLUMN
--   performance_mark (numeric 0–2) STAYS, read-only history: months already
--   calculated with a typed multiplier keep showing and re-deriving exactly
--   what they were. New writes go through the reference; the service clears
--   the legacy value when a šifarnik mark is chosen, and the CHECK below
--   refuses a row carrying both, so no row can be ambiguous about which
--   semantics it uses.
--
-- WHY NO ON DELETE RULE
--   The šifarnik archives (archived_at) instead of deleting, and the plain FK
--   makes a hard DELETE of a referenced version refuse — exactly right for a
--   row some month's pay was derived from.
--
--   · Rollback: ALTER TABLE payroll_run_items
--         DROP COLUMN performance_mark_id,
--         then restore the V18 CHECKs and the V19 trigger (both below).
-- =============================================================================

ALTER TABLE payroll_run_items
    ADD COLUMN performance_mark_id bigint REFERENCES performance_marks (id);

COMMENT ON COLUMN payroll_run_items.performance_mark_id IS
    'The šifarnik VERSION (performance_marks row) chosen as this month''s ocena. '
    'Adjusts the base rate when performance_mark_applied: PERCENT = base × '
    '(1 + amount/100), RSD_PER_HOUR = base + amount, floored at 0. Mutually '
    'exclusive with the legacy typed performance_mark.';
COMMENT ON COLUMN payroll_run_items.performance_mark IS
    'LEGACY typed ocena, 0–2, multiplying the base rate when applied. Kept for '
    'months calculated before the šifarnik; new marks are performance_mark_id. '
    'A row never carries both.';

-- One semantics per row: either the legacy multiplier or the šifarnik
-- reference, never both — the derivation must not have to pick a winner.
ALTER TABLE payroll_run_items
    ADD CONSTRAINT chk_payroll_run_items_performance_mark_one_kind
        CHECK (performance_mark IS NULL OR performance_mark_id IS NULL);

-- The V18 attribution and applied-state guards, restated so a CHOSEN mark is a
-- mark too: whichever form it takes, it is signed, and applied still means
-- "there IS one and somebody put it in force".
ALTER TABLE payroll_run_items
    DROP CONSTRAINT chk_payroll_run_items_performance_mark_attribution,
    ADD CONSTRAINT chk_payroll_run_items_performance_mark_attribution
        CHECK ((performance_mark IS NULL AND performance_mark_id IS NULL)
            OR (performance_mark_by IS NOT NULL AND performance_mark_at IS NOT NULL)),
    DROP CONSTRAINT chk_payroll_run_items_performance_mark_applied_state,
    ADD CONSTRAINT chk_payroll_run_items_performance_mark_applied_state
        CHECK (performance_mark_applied = false
            OR ((performance_mark IS NOT NULL OR performance_mark_id IS NOT NULL)
                AND performance_mark_applied_by IS NOT NULL
                AND performance_mark_applied_at IS NOT NULL));

-- Recreated rather than added to (a trigger's WHEN cannot be altered in
-- place), strictly wider than V19's: every update that fired it still does,
-- plus a change of the chosen šifarnik mark.
DROP TRIGGER IF EXISTS trg_audit_logs_payroll_run_items_human_input ON payroll_run_items;

CREATE TRIGGER trg_audit_logs_payroll_run_items_human_input
    AFTER UPDATE ON payroll_run_items
    FOR EACH ROW
    WHEN (OLD.hourly_rate_overridden     IS DISTINCT FROM NEW.hourly_rate_overridden
       OR OLD.hourly_rate_manual         IS DISTINCT FROM NEW.hourly_rate_manual
       OR OLD.performance_mark           IS DISTINCT FROM NEW.performance_mark
       OR OLD.performance_mark_id        IS DISTINCT FROM NEW.performance_mark_id
       OR OLD.performance_mark_applied   IS DISTINCT FROM NEW.performance_mark_applied
       OR OLD.director_note              IS DISTINCT FROM NEW.director_note)
    EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TRIGGER trg_audit_logs_payroll_run_items_human_input ON payroll_run_items IS
    'PARTIAL audit. Fires only when a value a PERSON enters actually changes — the '
    'typed hourly rate, the flag that records it, the performance mark (typed or '
    'chosen from the šifarnik), whether that mark is in force, and the director''s '
    'note on the payslip. Everything else on the row is derived.';

-- The service refuses a mark whose validity window misses the payroll month's
-- first day; the index is that lookup's and the admin screen's companion when
-- asking "which payrolls chose this version".
CREATE INDEX idx_payroll_run_items_performance_mark_id
    ON payroll_run_items (performance_mark_id)
    WHERE performance_mark_id IS NOT NULL;
