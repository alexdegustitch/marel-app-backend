-- =============================================================================
-- What the supervisor handed over
-- =============================================================================
-- THE CHANGE
-- payroll_run_items.status moved between exactly two values in code: DRAFT and
-- LOCKED. The state everyone talks about — "spreman", the moment a supervisor
-- says a month is done and hands it to payroll — did not exist. The status
-- CHECK constraint has allowed APPROVED since the table was made, but nothing
-- ever wrote it.
--
-- The chain becomes DRAFT -> APPROVED -> LOCKED, and every step between the
-- first two is recorded here.
--
-- WHY A TABLE AND NOT TWO COLUMNS
-- approved_by/approved_at could only ever hold the LAST handover. The real
-- workflow is a sequence: handed over, returned for correction, handed over
-- again. Columns overwrite; the sequence is the thing a dispute needs, so it
-- gets rows. The table is append-only and a trigger enforces that — an audit
-- record that can be edited is not one.
--
-- WHY THE FIGURES ARE HERE
-- The question this exists to answer is "the supervisor says they handed over
-- X, payroll paid Y — what happened". That needs the totals AS THEY WERE at the
-- moment of handover, because the live figures move: this system recalculates
-- aggressively (DailyRecalcService, AffectedMonthsRecalculator), so by the time
-- anybody asks, payroll_run_items no longer holds what was handed over.
--
-- NO ROLE DIMENSION, DELIBERATELY.
-- These are the real figures, once. Who may LOOK at them is decided when the
-- row is read, by PayrollVisibilityPolicy — the same filter that already
-- withholds amounts from roles that are not payroll. Storing a value per role
-- would mean the record no longer states what was true, and a later change to
-- who is allowed to see salaries would have to rewrite history it never stored.
--
-- WHAT THIS DOES NOT COVER
-- Locking keeps its own attribution in payroll_run_items.locked_at/locked_by.
-- Folding those in here would be a wider change than the handover this table is
-- about, and the lock has never been the disputed step.
--
-- IMPACT
-- New table only; nothing existing is altered or removed. It starts empty,
-- because no handover has ever been recorded. The 1084 items currently sitting
-- in DRAFT are untouched and stay valid — they simply have no handover yet.
-- Reversible with DROP TABLE plus a code revert; no data is lost by doing so.
-- =============================================================================

CREATE TABLE payroll_run_item_handovers (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    payroll_run_item_id  BIGINT NOT NULL
                             REFERENCES payroll_run_items (id) ON DELETE CASCADE,

    -- SUBMITTED = DRAFT -> APPROVED. RETURNED = APPROVED -> DRAFT.
    event                VARCHAR(255) NOT NULL,

    -- Null only if the actor was removed afterwards; the event still happened.
    actor_id             BIGINT REFERENCES users (id),
    occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    status_before        VARCHAR(255) NOT NULL,
    status_after         VARCHAR(255) NOT NULL,

    -- The figures at that moment. Null where the item had not been calculated.
    total_net_earnings   NUMERIC(38, 2),
    net_payable_amount   NUMERIC(38, 2),

    -- Why it was sent back. Meaningful on RETURNED, optional on SUBMITTED.
    note                 TEXT,

    CONSTRAINT chk_prih_event
        CHECK (event IN ('SUBMITTED', 'RETURNED'))
);

-- The list is always read per item, newest first.
CREATE INDEX idx_prih_item_occurred
    ON payroll_run_item_handovers (payroll_run_item_id, occurred_at DESC);

-- ── Append-only ──────────────────────────────────────────────────────────────
-- Enforced in the database rather than trusted to the service: this is the
-- record that settles an argument between two people, and "we only ever insert"
-- is a promise the schema should keep on its own.

CREATE OR REPLACE FUNCTION payroll_run_item_handovers_are_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'payroll_run_item_handovers is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prih_append_only
    BEFORE UPDATE OR DELETE ON payroll_run_item_handovers
    FOR EACH ROW
    EXECUTE FUNCTION payroll_run_item_handovers_are_append_only();
