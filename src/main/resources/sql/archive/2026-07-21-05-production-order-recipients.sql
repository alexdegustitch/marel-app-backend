-- =============================================================================
-- Production-order mailing lists and recipient snapshot
-- =============================================================================
-- Two distinct things are recorded, and they must not be collapsed into one:
--
--   production_order_mailing_lists  — WHICH lists a user selected (intent)
--   production_order_recipients     — WHO the order actually mails (snapshot)
--
-- Adding a list copies its currently active members into the snapshot. From that
-- moment the snapshot is independent: editing the mailing list later never
-- rewrites an existing production order's recipients. Email for a production
-- order is always sent from production_order_recipients, never by re-resolving
-- mailing-list membership.
-- =============================================================================

CREATE TABLE IF NOT EXISTS production_order_mailing_lists (
    -- Surrogate key rather than a composite PK: audit_trigger_fn records
    -- NEW.id/OLD.id, so an audited table must have a single-column id. The
    -- "same list only once per order" rule is enforced just as strictly by the
    -- UNIQUE constraint below, including against concurrent attach attempts.
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_id BIGINT      NOT NULL,
    mailing_list_id     BIGINT      NOT NULL,
    added_by            BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_production_order_mailing_lists
        UNIQUE (production_order_id, mailing_list_id),

    CONSTRAINT fk_po_mailing_lists_production_order_id
        FOREIGN KEY (production_order_id) REFERENCES production_orders (id) ON DELETE CASCADE,
    -- RESTRICT, not CASCADE: a mailing list is archived, never deleted, precisely
    -- so production-order history survives.
    CONSTRAINT fk_po_mailing_lists_mailing_list_id
        FOREIGN KEY (mailing_list_id) REFERENCES mailing_lists (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_mailing_lists_added_by
        FOREIGN KEY (added_by) REFERENCES users (id) ON DELETE RESTRICT
);

-- "which orders used this list" (impact analysis before archiving a list).
CREATE INDEX IF NOT EXISTS idx_po_mailing_lists_mailing_list_id
    ON production_order_mailing_lists (mailing_list_id);

COMMENT ON TABLE production_order_mailing_lists IS
    'Which mailing lists were selected for a production order. Records intent; the actual recipients live in production_order_recipients.';


CREATE TABLE IF NOT EXISTS production_order_recipients (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_id    BIGINT       NOT NULL,
    user_id                BIGINT,
    -- Always populated, even when user_id is set. This is the address the order
    -- was actually addressed to; if the user later changes their email, history
    -- must still show where the mail went.
    recipient_email        VARCHAR(320) NOT NULL,
    recipient_name         VARCHAR(150),
    source_type            VARCHAR(20)  NOT NULL,
    source_mailing_list_id BIGINT,
    added_by               BIGINT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    removed_at             TIMESTAMPTZ,
    removed_by             BIGINT,

    CONSTRAINT fk_po_recipients_production_order_id
        FOREIGN KEY (production_order_id) REFERENCES production_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_po_recipients_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_recipients_source_mailing_list_id
        FOREIGN KEY (source_mailing_list_id) REFERENCES mailing_lists (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_recipients_added_by
        FOREIGN KEY (added_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_recipients_removed_by
        FOREIGN KEY (removed_by) REFERENCES users (id) ON DELETE RESTRICT,

    -- MAILING_LIST : copied from a selected list
    -- MANUAL       : typed in by a user for this order only
    -- SYSTEM       : added by backend logic (e.g. the order's responsible user)
    CONSTRAINT chk_po_recipients_source_type
        CHECK (source_type IN ('MAILING_LIST', 'MANUAL', 'SYSTEM')),

    -- The source list is recorded exactly when the source IS a list.
    CONSTRAINT chk_po_recipients_source_list_consistency
        CHECK (
            (source_type = 'MAILING_LIST' AND source_mailing_list_id IS NOT NULL)
            OR (source_type <> 'MAILING_LIST' AND source_mailing_list_id IS NULL)
        ),

    -- A SYSTEM recipient has no human author; every other source does.
    CONSTRAINT chk_po_recipients_added_by
        CHECK (
            (source_type = 'SYSTEM' AND added_by IS NULL)
            OR (source_type <> 'SYSTEM' AND added_by IS NOT NULL)
        ),

    -- Removal is an archive and is always attributable.
    CONSTRAINT chk_po_recipients_removal_state
        CHECK (
            (removed_at IS NULL AND removed_by IS NULL)
            OR (removed_at IS NOT NULL AND removed_by IS NOT NULL)
        ),

    CONSTRAINT chk_po_recipients_email
        CHECK (
            recipient_email = lower(trim(recipient_email))
            AND recipient_email LIKE '%_@_%._%'
            AND recipient_email !~ '[[:space:]]'
        ),

    CONSTRAINT chk_po_recipients_name
        CHECK (recipient_name IS NULL OR length(trim(recipient_name)) > 0)
);

-- THE deduplication guarantee: one active recipient per normalized address per
-- production order. A person who appears in three selected mailing lists, or in
-- a list AND as a manual entry, still receives exactly one email. Enforced in the
-- database so two concurrent "add mailing list" transactions cannot both insert
-- the same address.
CREATE UNIQUE INDEX IF NOT EXISTS uq_po_recipients_order_email_active
    ON production_order_recipients (production_order_id, lower(recipient_email))
    WHERE removed_at IS NULL;

-- The send path: active recipients of an order.
CREATE INDEX IF NOT EXISTS idx_po_recipients_order_active
    ON production_order_recipients (production_order_id)
    WHERE removed_at IS NULL;

-- Detaching a mailing list has to find the rows it contributed.
CREATE INDEX IF NOT EXISTS idx_po_recipients_source_mailing_list
    ON production_order_recipients (production_order_id, source_mailing_list_id)
    WHERE source_mailing_list_id IS NOT NULL AND removed_at IS NULL;

COMMENT ON TABLE production_order_recipients IS
    'Immutable-by-intent recipient snapshot for a production order. Later mailing-list edits never rewrite it. Removal sets removed_at/removed_by.';
COMMENT ON COLUMN production_order_recipients.recipient_email IS
    'The address actually used for this order, snapshotted at add time even when user_id is present.';

-- DEDUPLICATION AND SOURCE ATTRIBUTION
-- When one address arrives from several selected mailing lists, exactly one row
-- is kept and source_mailing_list_id names the FIRST list that contributed it.
-- No additional recipient-source link table is created: no current audit
-- requirement asks "which other lists also contained this address", the
-- production_order_mailing_lists rows already record every selected list, and
-- audit_logs records each insert. Adding the table speculatively is exactly the
-- over-modelling this schema is meant to avoid.

-- MAILING-LIST DETACHMENT RULE (one explicit rule, chosen to match the existing
-- production-order lifecycle CREATED -> DELIVERED):
--   * While the order is CREATED, detaching a mailing list also archives the
--     active recipients whose ONLY source was that list (source_type =
--     'MAILING_LIST' AND source_mailing_list_id = the detached list). MANUAL and
--     SYSTEM recipients are never touched, and neither is a row already
--     re-attributed to another list.
--   * Once the order is DELIVERED the snapshot is locked: no attach, no detach,
--     no manual add, no removal. DELIVERED is the terminal state at which the
--     order has been communicated, so its recipient history must stop moving.
-- Enforced by ProductionOrderRecipientService, not by a trigger — the repository
-- keeps workflow rules in the service layer and uses triggers only for
-- timestamps, archiving and auditing.
