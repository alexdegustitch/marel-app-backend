-- =============================================================================
-- Asking for a finished month back
-- =============================================================================
-- WHAT CHANGES
--   payroll_change_requests — new. One supervisor's request to reopen a payroll,
--     with the reason, and what payroll decided about it.
--   payroll_run_item_handovers.chk_prih_event — three more events, so the
--     request and its outcome appear in the payroll's own history.
--   audit_tables — the new table registered, and audited.
--
-- WHY AT ALL
--   Until now a submitted month could be pulled back by whoever held
--   PAYROLL_HANDOVER — which is the supervisor. That is the wrong way round
--   once payroll has begun working on it: the shop floor said the month was
--   finished, payroll started from that, and taking it back underneath them
--   silently invalidates whatever they have done since.
--
--   So the direction reverses. The supervisor ASKS, with a reason, and payroll
--   answers. Nothing about the payroll moves until somebody with the authority
--   over it says so.
--
-- WHY A TABLE AND NOT A FLAG ON THE ITEM
--   A request has a reason, an author, a decision, a decider and a moment — and
--   there can be a second one after the first is refused. A boolean on the item
--   could hold none of that, and the question people actually ask afterwards is
--   "who asked for this month back, and what did they say", which is a row.
--
-- WHY THE ITEM MAY BE LOCKED AND NOT ONLY SUBMITTED
--   An error is as likely to be noticed after the month is frozen as before.
--   Accepting then takes the payroll from LOCKED straight to DRAFT — one
--   decision, one step — rather than making payroll unlock and then reopen, two
--   acts for one intention with a state in between that means nothing.
--
-- WHY AT MOST ONE OPEN REQUEST PER PAYROLL
--   uq_pcr_open_per_item, a partial unique index on the PENDING ones. Two open
--   requests for the same month are two answers to give and one thing to do; the
--   second asker should be told there is already one waiting rather than adding
--   to a queue nobody reads twice.
--
-- WHY THE HANDOVER EVENTS
--   The request and its outcome belong in the payroll's own history, beside the
--   submission they are about — that timeline is where somebody looks to find
--   out why a finished month is open again. chk_prih_event is a closed list, and
--   an INSERT against it fails the transaction that writes the step, so the list
--   has to be widened before the step can exist.
--
-- MIGRATION IMPACT
--   Additive. One new table, one CHECK replaced by a strictly WIDER one (every
--   value that satisfied the old list satisfies the new), two audit_tables rows
--   and one trigger. No existing row changes, no column is dropped, and no
--   payroll figure moves. Safe on a live database.
--
--   Rollback: DROP TABLE payroll_change_requests; restore chk_prih_event to its
--   four-value list once any CHANGE_* steps written since are removed; DELETE
--   FROM audit_tables WHERE table_name = 'payroll_change_requests'.
-- =============================================================================


CREATE TABLE payroll_change_requests (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    payroll_run_item_id BIGINT      NOT NULL,

    -- Who is asking. NOT NULL: a request nobody signed is a request nobody can
    -- be asked about, and the whole point is that somebody took responsibility
    -- for wanting the month back.
    requested_by        BIGINT      NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Why. Compulsory, and the reason this is a request rather than a button:
    -- "the month is wrong" is not something payroll can act on.
    reason              TEXT        NOT NULL,

    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Who answered, when, and what they said about it. All null while PENDING.
    decided_by          BIGINT,
    decided_at          TIMESTAMPTZ,
    decision_note       TEXT,

    CONSTRAINT fk_pcr_payroll_run_item
        FOREIGN KEY (payroll_run_item_id) REFERENCES payroll_run_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_pcr_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_pcr_decided_by   FOREIGN KEY (decided_by)   REFERENCES users (id),

    CONSTRAINT chk_pcr_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),

    CONSTRAINT chk_pcr_reason_not_empty
        CHECK (length(btrim(reason)) > 0),

    -- Decided means somebody decided it. All three together or none — a status
    -- that moved with nobody's name on it is the gap this table exists to close.
    CONSTRAINT chk_pcr_decision_state
        CHECK ((status =  'PENDING' AND decided_by IS     NULL AND decided_at IS     NULL)
            OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),

    CONSTRAINT chk_pcr_decision_note_not_empty
        CHECK (decision_note IS NULL OR length(btrim(decision_note)) > 0)
);

-- One open request per payroll. The service checks first for a sentence somebody
-- can act on; THIS is what makes it true when two supervisors ask at once.
CREATE UNIQUE INDEX uq_pcr_open_per_item
    ON payroll_change_requests (payroll_run_item_id)
    WHERE status = 'PENDING';

-- What the requests screen asks for: everything still waiting, newest first.
CREATE INDEX idx_pcr_pending
    ON payroll_change_requests (requested_at DESC)
    WHERE status = 'PENDING';

-- And what one payroll's own page asks for.
CREATE INDEX idx_pcr_item
    ON payroll_change_requests (payroll_run_item_id, requested_at DESC);

COMMENT ON TABLE payroll_change_requests IS
    'A supervisor asking payroll to reopen a submitted or locked month, with the '
    'reason, and payroll''s answer. Accepting takes the item to DRAFT — from '
    'LOCKED in one step, because unlocking and then reopening are one intention.';


-- === The payroll's history learns about the request ==========================

ALTER TABLE payroll_run_item_handovers
    DROP CONSTRAINT IF EXISTS chk_prih_event;

ALTER TABLE payroll_run_item_handovers
    ADD CONSTRAINT chk_prih_event
        CHECK (event IN ('SUBMITTED', 'RETURNED', 'LOCKED', 'UNLOCKED',
                         'CHANGE_REQUESTED', 'CHANGE_ACCEPTED', 'CHANGE_DECLINED'));

COMMENT ON COLUMN payroll_run_item_handovers.event IS
    'One step of the payroll''s history. SUBMITTED and RETURNED between the shop '
    'floor and payroll; LOCKED and UNLOCKED when payroll freezes the month or '
    'reopens it; CHANGE_REQUESTED, CHANGE_ACCEPTED and CHANGE_DECLINED for a '
    'supervisor asking a finished month back. A closed list, because the screen '
    'maps each value to a label and an undefined event would be printed raw.';


-- === Auditing ================================================================
-- audit_trigger_fn resolves the table by NAME against audit_tables and writes
-- the id into audit_logs.table_id, which is NOT NULL. An unregistered table does
-- not merely go unaudited — the first insert into it fails. Registration first.

INSERT INTO audit_tables (table_name)
SELECT t.name
  FROM (VALUES ('payroll_change_requests')) AS t(name)
 WHERE NOT EXISTS (
     SELECT 1 FROM audit_tables a WHERE a.table_name = t.name
 );

CREATE TRIGGER trg_audit_logs_payroll_change_requests
    AFTER INSERT OR DELETE OR UPDATE ON payroll_change_requests
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
