-- =============================================================================
-- Locking a month is part of its history
-- =============================================================================
-- WHAT CHANGES
--   payroll_run_item_handovers.chk_prih_event — widened from two events to four.
--
-- WHY
--   The handover chain records what happened to a payroll: created, submitted,
--   sent back, submitted again. It stopped there. A month that has been frozen
--   and paid read, in its own history, as though it were still sitting with
--   payroll waiting to be looked at — and reopening one left no trace at all.
--
--   Locking IS the last step of that chain, not a separate log: "what happened
--   to this payroll" is one question and the answer has to end with the answer.
--
-- WHY THE CONSTRAINT HAS TO MOVE FIRST
--   chk_prih_event is a closed list. Writing a LOCKED step against it fails the
--   INSERT — and because the step is recorded in the same transaction as the
--   lock itself, the lock would fail with it. The column is deliberately closed
--   rather than free text (an event nobody defined is a row nobody can read), so
--   widening it is how a new step is introduced.
--
-- WHY NOT DROP THE CHECK ALTOGETHER
--   Because the timeline on screen maps each event to a label. An unrecognised
--   value falls through to the raw string, and a payroll history that says
--   "LCOKED" to one reader is worse than one that refused the typo on the way in.
--
-- MIGRATION IMPACT
--   One CHECK replaced by a strictly WIDER one: every value that satisfied the
--   old list satisfies the new list, so no existing row can fail and the
--   constraint cannot fail to validate. Nothing is dropped, no column changes,
--   no payroll figure moves. Reversible by restoring the two-value list, which
--   is only safe once any LOCKED/UNLOCKED rows written since are removed.
-- =============================================================================

ALTER TABLE payroll_run_item_handovers
    DROP CONSTRAINT IF EXISTS chk_prih_event;

ALTER TABLE payroll_run_item_handovers
    ADD CONSTRAINT chk_prih_event
        CHECK (event IN ('SUBMITTED', 'RETURNED', 'LOCKED', 'UNLOCKED'));

COMMENT ON COLUMN payroll_run_item_handovers.event IS
    'One step of the payroll''s history: SUBMITTED and RETURNED between the shop '
    'floor and payroll, LOCKED and UNLOCKED when payroll freezes the month or '
    'reopens it. A closed list, because the screen maps each value to a label and '
    'an undefined event would be printed to the reader raw.';
