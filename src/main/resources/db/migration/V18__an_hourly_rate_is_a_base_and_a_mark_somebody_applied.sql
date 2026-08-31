-- =============================================================================
-- An hourly rate is a BASE and a mark somebody applied to it
-- =============================================================================
-- WHAT CHANGES
--   payroll_run_items — seven new columns:
--     hourly_rate_manual            what a person TYPED, as opposed to what the
--                                   rate ends up being
--     performance_mark              the ocena, 0–2
--     performance_mark_by / _at     who gave it, and when
--     performance_mark_applied      whether the rate is currently multiplied by it
--     performance_mark_applied_by / _at   who pressed "primeni", and when
--   The partial audit trigger is widened to fire on all four human inputs.
--
-- WHY AT ALL
--   An employee's month can be worth more or less than their rate says, and
--   payroll wants that said as one number — a mark of 1.1, 1.0, 0.9 — rather
--   than by re-typing an hourly rate and leaving nobody able to see why it
--   moved. The supervisor gives the mark; the administrator decides whether it
--   takes effect. Two people, two decisions, and until now the schema could
--   record neither.
--
-- WHY hourly_rate BECOMES DERIVED, AND WHY THAT NEEDS A NEW COLUMN
--   Three columns exist today: hourly_rate (what is used), hourly_rate_system
--   (what the employee's rate history says) and hourly_rate_overridden (a
--   person typed their own). That is one column short of what a mark needs,
--   because hourly_rate is BOTH "the typed value" and "the value in force". The
--   moment a mark multiplies it, those stop being the same thing and the typed
--   value has nowhere left to live — applying a mark twice would compound, and
--   "vrati na prethodnu vrednost" would have nothing to return to.
--
--   So the typed value moves to hourly_rate_manual and hourly_rate becomes
--   derived from three inputs that never overwrite each other:
--
--       base      = COALESCE(hourly_rate_manual, hourly_rate_system)
--       in force  = performance_mark_applied ? round(base * mark, 2) : base
--
--   Chosen over the alternative — snapshotting the pre-mark value into a column
--   and overwriting hourly_rate — because the system rate can move while a mark
--   is applied. With a snapshot, "vrati" would restore a figure that is no
--   longer anybody's rate and the mark would silently stop tracking. Derived,
--   the mark re-applies to whatever the base is now, which is what a mark means.
--
--   hourly_rate is still WRITTEN to the row, not computed on read: every
--   downstream reader — the categories, the totals, the payslip, the reports —
--   goes on reading one column that means "the rate this month was calculated
--   at". Nothing outside PayrollRunItemService changes.
--
-- WHAT hourly_rate_overridden NOW MEANS
--   Exactly what it always meant in practice — "a person typed this rate" — but
--   stated as `hourly_rate_manual IS NOT NULL` rather than inferred from
--   hourly_rate <> hourly_rate_system. The two agree everywhere except on a row
--   whose rate differs from the system's BECAUSE OF A MARK, where the old test
--   would have said "overridden" about a value nobody typed. The service keeps
--   writing NULL to hourly_rate_manual when the typed figure equals the system
--   one, so a person typing the system value still reads as not overridden,
--   exactly as before.
--
-- WHY numeric AND NOT double precision
--   The mark multiplies what somebody is paid. A binary float cannot hold 1.1,
--   so the same mark applied twice could produce two different rates and no
--   later reader could reproduce either. Every money column on this table is
--   already numeric; the multiplier has to be too.
--
-- WHY THE RANGE IS A CHECK AND NOT A VALIDATION
--   0–2 is stated in the schema because a mark of 20 would multiply somebody's
--   salary by twenty, and the service validating it is only true of the writers
--   that go through the service.
--
-- WHY THE AUDIT TRIGGER HAS TO BE WIDENED
--   trg_audit_logs_payroll_run_items_human_input is deliberately PARTIAL: it
--   fires only on values a person enters, because a payroll item is rewritten by
--   every lazy recalculation and a full row audit would bury the decisions in
--   churn. The mark, who applied it, and the typed rate are exactly that kind of
--   value, so they join the WHEN clause. Without this, giving somebody a 0.9 and
--   applying it would leave no trace of who did it — the one thing this table's
--   audit exists to record.
--
-- MIGRATION IMPACT
--   Additive. Seven nullable-or-defaulted columns, four CHECKs that no existing
--   row can fail (every one of them is vacuous while performance_mark IS NULL
--   and hourly_rate_manual IS NULL), and one trigger recreated with a strictly
--   WIDER condition — it fires in every case it fired in before, plus three.
--
--   The one backfill copies hourly_rate into hourly_rate_manual for rows already
--   marked overridden. That is a restatement, not a change: those rows already
--   hold a typed value in hourly_rate, and after it the derivation gives back
--   the same hourly_rate they have now (no mark is applied on any row, so
--   `in force = base = hourly_rate_manual`). No payroll figure moves.
--
--   Reversible: the columns can be dropped and the trigger restored to its
--   one-condition form. Nothing outside payroll_run_items is touched.
-- =============================================================================


-- === The typed rate gets a column of its own ================================

ALTER TABLE payroll_run_items
    ADD COLUMN hourly_rate_manual numeric(38,2);

COMMENT ON COLUMN payroll_run_items.hourly_rate_manual IS
    'The hourly rate a PERSON typed for this item. NULL means nobody did and the '
    'system rate stands. Distinct from hourly_rate, which is what the month was '
    'actually calculated at — the two differ whenever a performance mark is applied.';

-- A restatement of what these rows already say, not a change to any of them.
UPDATE payroll_run_items
   SET hourly_rate_manual = hourly_rate
 WHERE hourly_rate_overridden
   AND hourly_rate_manual IS NULL;

ALTER TABLE payroll_run_items
    ADD CONSTRAINT chk_payroll_run_items_hourly_rate_manual_not_negative
        CHECK (hourly_rate_manual IS NULL OR hourly_rate_manual >= 0);


-- === The mark ================================================================

ALTER TABLE payroll_run_items
    -- Two decimals: the marks people actually give are 0.9, 1.0, 1.1, 1.15.
    ADD COLUMN performance_mark            numeric(4,2),
    ADD COLUMN performance_mark_by         bigint,
    ADD COLUMN performance_mark_at         timestamptz,

    -- NOT NULL with a default rather than nullable: "is the mark in force" is a
    -- yes/no about every item, including the ones that have no mark, and a NULL
    -- third state would be a question nobody asked.
    ADD COLUMN performance_mark_applied    boolean NOT NULL DEFAULT false,
    ADD COLUMN performance_mark_applied_by bigint,
    ADD COLUMN performance_mark_applied_at timestamptz;

ALTER TABLE payroll_run_items
    ADD CONSTRAINT fk_payroll_run_items_performance_mark_by
        FOREIGN KEY (performance_mark_by) REFERENCES users (id),
    ADD CONSTRAINT fk_payroll_run_items_performance_mark_applied_by
        FOREIGN KEY (performance_mark_applied_by) REFERENCES users (id);

ALTER TABLE payroll_run_items
    -- A mark of 20 would multiply somebody's salary by twenty. Stated here so it
    -- is true of every writer, not only the ones that go through the service.
    ADD CONSTRAINT chk_payroll_run_items_performance_mark_range
        CHECK (performance_mark IS NULL
            OR (performance_mark >= 0 AND performance_mark <= 2)),

    -- A mark nobody signed is a mark nobody can be asked about.
    ADD CONSTRAINT chk_payroll_run_items_performance_mark_attribution
        CHECK (performance_mark IS NULL
            OR (performance_mark_by IS NOT NULL AND performance_mark_at IS NOT NULL)),

    -- Applied means: there IS a mark, and somebody put it in force. All three or
    -- none — an applied flag without a mark would leave the derivation
    -- multiplying by nothing.
    ADD CONSTRAINT chk_payroll_run_items_performance_mark_applied_state
        CHECK (performance_mark_applied = false
            OR (performance_mark IS NOT NULL
                AND performance_mark_applied_by IS NOT NULL
                AND performance_mark_applied_at IS NOT NULL));

COMMENT ON COLUMN payroll_run_items.performance_mark IS
    'The ocena, 0–2. Multiplies the base hourly rate WHEN performance_mark_applied. '
    'Giving a mark changes nothing on its own — applying it is a separate decision '
    'by a separate person.';
COMMENT ON COLUMN payroll_run_items.performance_mark_applied IS
    'Whether hourly_rate is currently base * performance_mark. Cleared when the '
    'rate is typed by hand, because the value then no longer comes from the mark.';


-- === The partial audit trigger learns about the three new decisions ==========
--
-- Recreated rather than added to: a trigger's WHEN clause cannot be altered in
-- place. The condition is strictly WIDER than before — every update that fired
-- it still fires it.

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_run_items_human_input ON payroll_run_items;

CREATE TRIGGER trg_audit_logs_payroll_run_items_human_input
    AFTER UPDATE ON payroll_run_items
    FOR EACH ROW
    WHEN (OLD.hourly_rate_overridden     IS DISTINCT FROM NEW.hourly_rate_overridden
       OR OLD.hourly_rate_manual         IS DISTINCT FROM NEW.hourly_rate_manual
       OR OLD.performance_mark           IS DISTINCT FROM NEW.performance_mark
       OR OLD.performance_mark_applied   IS DISTINCT FROM NEW.performance_mark_applied)
    EXECUTE FUNCTION audit_trigger_fn();

COMMENT ON TRIGGER trg_audit_logs_payroll_run_items_human_input ON payroll_run_items IS
    'PARTIAL audit. Fires only when a value a PERSON enters actually changes — the '
    'typed hourly rate, the flag that records it, the performance mark, and whether '
    'that mark is in force. Everything else on the item is recalculated constantly '
    'and is auditable through payroll_adjustments. Do not read the payroll_run_items '
    'row in audit_tables as full coverage.';
