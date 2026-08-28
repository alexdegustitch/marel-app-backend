-- =============================================================================
-- An order has ONE e-mail conversation, not one mail per person
-- =============================================================================
-- WHAT CHANGES
--   production_order_email_threads — new. One row per order: the subject that
--     never changes, and the Message-ID chain every later mail hangs off.
--   notification_deliveries — five nullable columns, and one CHECK constraint
--     relaxed.
--
-- WHY AT ALL
--   Mail clients group messages into a conversation by the References chain:
--   a message belongs to a thread when it names an ancestor the others also
--   name. Nothing in this schema remembered what we had already sent, so every
--   notification about an order arrived as an unrelated message. Fifteen
--   changes to one order produced fifteen separate mails in the inbox.
--
--   Recording the chain is the whole feature. Once an order's root Message-ID
--   is stored, every later mail can say "this continues that", and the client
--   does the rest — Gmail, Outlook and Apple Mail all honour it.
--
-- WHY ONE ROW PER ORDER AND NOT PER RECIPIENT
--   The conversation is internal: the same colleagues, all named in To. One
--   message with five recipients gives all five the SAME Message-ID, so when
--   one of them hits Reply All the answer lands in everybody's thread.
--
--   Sending five separate mails would give each recipient a private chain. A
--   reply would then reference an id the other four never received, and their
--   clients would show it as a new conversation. The thread would fracture
--   along exactly the seam where a discussion starts. Hence: one row here, one
--   message, many addresses.
--
-- WHY message_id LIVES ON THE DELIVERY ROW
--   It is written when the row is created, not when the mail is handed to the
--   provider — so a retry re-sends the SAME id. That matters: if a send
--   succeeded but the acknowledgement was lost, the retry is a byte-identical
--   duplicate and every client discards it silently. Generating the id at send
--   time would instead put the same message in the thread twice, with nothing
--   in the logs to say so.
--
-- WHY THE CHECK CONSTRAINT HAS TO MOVE
--   chk_notification_deliveries_target demanded recipient_email for every EMAIL
--   row. A group send has no single recipient — the addresses live in
--   recipient_emails — so the rule becomes "one of the two is present". Single
--   sends (registration decisions) are untouched and still fill recipient_email.
--
-- MIGRATION IMPACT
--   Nothing is dropped and no existing column changes meaning. Every new column
--   is nullable, so rows written before today stay valid and keep sending
--   exactly as they did: NULL recipient_emails means "single recipient", which
--   is what they all are. The constraint is only widened, never narrowed, so no
--   existing row can fail it. Safe to run on a live database.
-- =============================================================================

CREATE TABLE production_order_email_threads (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    production_order_id BIGINT       NOT NULL,

    -- Without "Re:", and never edited afterwards. Clients also weigh the
    -- subject when grouping, so a changed subject can split a conversation even
    -- when the References chain is intact. Freezing it here is what stops a
    -- later template edit from quietly breaking every open thread.
    subject_base        VARCHAR(255) NOT NULL,

    -- The first message we sent. Kept for the record even after the chain grows.
    root_message_id     VARCHAR(255) NOT NULL,

    -- What the next mail sets as In-Reply-To.
    last_message_id     VARCHAR(255) NOT NULL,

    -- Every id sent so far, space separated, oldest first — the References
    -- header verbatim. TEXT because a long-running order can accumulate dozens
    -- and truncating it in the middle would break the very grouping it exists
    -- for.
    references_chain    TEXT         NOT NULL DEFAULT '',

    -- Source of the per-message counter in generated ids, so two mails in the
    -- same second cannot collide.
    message_count       INTEGER      NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,

    CONSTRAINT fk_poet_production_order
        FOREIGN KEY (production_order_id) REFERENCES production_orders (id),

    -- One conversation per order. This is the rule the whole feature rests on,
    -- so the database states it rather than the service hoping for it.
    CONSTRAINT uq_poet_production_order UNIQUE (production_order_id),

    CONSTRAINT chk_poet_message_count CHECK (message_count >= 0)
);

COMMENT ON TABLE production_order_email_threads IS
    'One e-mail conversation per production order: frozen subject plus the Message-ID chain later mails attach to.';


ALTER TABLE notification_deliveries
    -- Comma separated. Present exactly when this row is one message addressed
    -- to several people; NULL means the single-recipient path in
    -- recipient_email, unchanged since V1.
    ADD COLUMN recipient_emails  TEXT,

    -- Written at row creation so retries reuse it. See above.
    ADD COLUMN message_id        VARCHAR(255),

    ADD COLUMN in_reply_to       VARCHAR(255),

    -- The conversation's frozen subject. Copied onto the row rather than looked
    -- up when sending, because the send runs outside the transaction on detached
    -- data — and because the subject must be what it was when this mail was
    -- queued, not what the thread says today.
    ADD COLUMN thread_subject    VARCHAR(255),

    -- Frozen copy of the thread's chain AS IT WAS when this row was created.
    -- Read from the thread at send time instead and a message queued yesterday
    -- would go out claiming to follow one sent this morning.
    ADD COLUMN references_header TEXT;

ALTER TABLE notification_deliveries
    DROP CONSTRAINT chk_notification_deliveries_target;

ALTER TABLE notification_deliveries
    ADD CONSTRAINT chk_notification_deliveries_target CHECK (
        (channel = 'EMAIL'  AND (recipient_email IS NOT NULL OR recipient_emails IS NOT NULL))
        OR
        (channel = 'IN_APP' AND recipient_user_id IS NOT NULL)
    );

-- A group send with an empty address list would be queued, claimed, and fail at
-- the relay every time until it exhausted its retries.
ALTER TABLE notification_deliveries
    ADD CONSTRAINT chk_notification_deliveries_recipient_emails CHECK (
        recipient_emails IS NULL OR length(btrim(recipient_emails)) > 0
    );

-- Replay safety for the group send.
--
-- uq_notification_deliveries_email keys on (event, lower(recipient_email)) and a
-- group row leaves that column NULL — and NULL is never equal to NULL, so two
-- replays of one outbox event would BOTH insert and the whole team would get the
-- same mail twice. The service checks first, but §7.2 of the notifications rules
-- puts this guarantee in the database rather than in a service's good intentions,
-- and a check-then-insert loses that race by construction.
--
-- One group mail per event, therefore, stated where two concurrent workers
-- cannot talk their way around it.
CREATE UNIQUE INDEX uq_notification_deliveries_group
    ON notification_deliveries (notification_event_id)
    WHERE channel = 'EMAIL' AND recipient_emails IS NOT NULL;


COMMENT ON COLUMN notification_deliveries.message_id IS
    'RFC 5322 Message-ID for this mail, assigned when the row is created so a retry re-sends an identical message the client can discard as a duplicate.';
