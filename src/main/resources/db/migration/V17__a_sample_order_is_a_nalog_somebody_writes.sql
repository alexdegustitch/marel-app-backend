-- =============================================================================
-- A sample order is a nalog somebody writes, not a table nobody can reach
-- =============================================================================
-- WHAT CHANGES
--   sample_orders                  — three new columns: code, note, deadline_note.
--   sample_order_line_items        — one new column: product_description.
--                                    One broken trigger repaired, one unique
--                                    constraint made partial.
--   sample_order_recipients        — new. Who a sample order tells.
--   sample_order_mailing_lists     — new. Which lists it was sent from.
--   sample_order_email_threads     — new. Its one e-mail conversation.
--   manufacturing_time_requests    — one nullable column, so a request can name
--                                    the sample-order line it was raised on.
--
-- WHY AT ALL
--   sample_orders and sample_order_line_items have existed since the baseline
--   with entities and repositories mapped onto them and NOTHING that writes to
--   them (V12 says so in as many words). The application is now growing the
--   screen those tables were always for: a nalog za izradu uzoraka, written the
--   way a production order is written, with its own list, its own detail page
--   and its own editing.
--
--   Four things the screen needs had nowhere to go.
--
--   · code — the šifra. A production order has one and people quote it; a
--     sample order had only a name, so two runs of the same samples for the
--     same customer were indistinguishable in conversation.
--   · note — a napomena about the WHOLE order, as opposed to the per-line note
--     that already exists.
--   · deadline_note — the rok in words. deadline_date carries the day; some
--     roks are "po dogovoru" or "kraj februara" and the date alone loses that.
--   · product_description — the opis a line carries for the shop floor, exactly
--     as production_order_line_items.product_description carries it. The line's
--     `note` is a different thing and was being asked to be both.
--
-- WHY code IS NOT NULL, AND WHY THE BACKFILL IS HERE ANYWAY
--   A nalog without a šifra is not a nalog anybody can refer to, so the column
--   is NOT NULL like production_orders.code. These tables have no write path
--   yet, so in every environment we know of they are empty and the backfill
--   below touches nothing. It is written regardless because "we think it is
--   empty" is not a thing to bet a failed migration on: any row that does exist
--   gets 'UZ-<id>', which is unmistakably generated and easy to correct.
--
--   Deliberately NOT unique. production_orders.code is not unique either, and
--   introducing the stricter rule on the newer table would make the two behave
--   differently for no reason anybody could state.
--
-- WHY THE set_sample_line_no TRIGGER IS REPAIRED RATHER THAN LEFT ALONE
--   The function reads `new.line_no`. There is no such column — the column is
--   `order_line`, and has been since the baseline. plpgsql resolves the field at
--   runtime, so the trigger does not fail until somebody inserts, and nobody
--   ever has. The first line item this feature writes would have failed with
--   "record new has no field line_no".
--
--   Repaired to name the real column rather than dropped: what it does is
--   correct and worth keeping — a line inserted without a position gets the next
--   one. The service sets the position explicitly (the form lets people drag
--   lines into order), so in practice the IF is false and the trigger no-ops.
--
-- WHY THE LINE-ORDER UNIQUE CONSTRAINT HAS TO BECOME PARTIAL
--   Editing an order does not rewrite its lines in place. It deactivates the
--   live ones and inserts a fresh set — the same soft-delete-and-reinsert
--   pattern production orders use, and the reason the history is auditable at
--   all. uq_sample_order_line_items_order_line is a plain UNIQUE over
--   (sample_order_id, order_line), so the deactivated line 1 would collide with
--   the new line 1 and the second save of any order would fail.
--
--   The rule people actually want is "one LIVE line per position", which is what
--   the partial unique index below states. Nothing is loosened for live data:
--   two active lines still cannot share a position. Only archived revisions,
--   which are history and not positions, are let through.
--
-- WHY THE THREE NEW TABLES MIRROR THE PRODUCTION-ORDER ONES
--   Same shape, same constraints, same reasons — see V14 for why one e-mail
--   conversation per order rather than one per recipient, and the recipient
--   table's own checks for why a snapshot is not a view over a mailing list.
--   They are separate tables rather than a shared one with a nullable pair of
--   foreign keys because a recipient belongs to exactly one kind of order, and a
--   shared table would need a CHECK to say so plus every query to remember it.
--
-- WHY manufacturing_time_requests GETS A SECOND OCCASION COLUMN
--   A request is ABOUT a product; the line is the occasion it was raised on.
--   Samples are exactly where somebody first notices a product has no
--   manufacturing time, so the sample-order line has to be able to be that
--   occasion. product_id stays NOT NULL and keeps its meaning.
--
--   The two occasion columns are mutually exclusive by CHECK: a request comes
--   from one line or from none, never from a production line and a sample line
--   at once. Same composite foreign key as V3 used, for the same reason — the
--   pair (line, product) is what is checked, so a request cannot claim to come
--   from a line for product A while asking about product B.
--
-- MIGRATION IMPACT
--   Additive except for two repairs, both of which only make previously
--   impossible writes possible:
--     · the trigger function is corrected — it could not have run successfully
--       before, so no existing row depended on its behaviour;
--     · the unique constraint is replaced by a partial one that forbids strictly
--       less. Every row that satisfied the old rule satisfies the new one, so
--       the index cannot fail to build.
--   Every new column is nullable or backfilled before being made NOT NULL. No
--   column is dropped, no column changes meaning, and nothing outside the
--   sample-order tables and the one new request column is touched. Safe to run
--   on a live database.
-- =============================================================================


-- === sample_orders: the šifra, the napomena, and the rok in words ============

ALTER TABLE sample_orders
    ADD COLUMN code          VARCHAR(255),
    -- A napomena about the whole order. TEXT rather than VARCHAR: this is the
    -- field people paste half a mail into, and a length limit here would only
    -- ever be discovered by losing somebody's paragraph.
    ADD COLUMN note          TEXT,
    -- The rok as a sentence: "po dogovoru", "kraj februara". Sits BESIDE
    -- deadline_date rather than replacing it — the date is what the list sorts
    -- and warns on, the sentence is what a person reads.
    ADD COLUMN deadline_note VARCHAR(255);

-- Empty everywhere we know of; written so that it is true even where it is not.
UPDATE sample_orders
   SET code = 'UZ-' || id
 WHERE code IS NULL;

ALTER TABLE sample_orders
    ALTER COLUMN code SET NOT NULL;

-- Mirrors chk_sample_orders_name_not_empty: a blank šifra is a missing šifra
-- wearing a space.
ALTER TABLE sample_orders
    ADD CONSTRAINT chk_sample_orders_code_not_empty
        CHECK (length(btrim(code)) > 0);

COMMENT ON COLUMN sample_orders.code IS
    'The šifra people quote the order by. Not unique, matching production_orders.code.';
COMMENT ON COLUMN sample_orders.note IS
    'A napomena about the whole order, as opposed to sample_order_line_items.note.';
COMMENT ON COLUMN sample_orders.deadline_note IS
    'The rok in words, beside deadline_date rather than instead of it.';


-- === sample_order_line_items: the opis the shop floor works from =============

ALTER TABLE sample_order_line_items
    ADD COLUMN product_description TEXT;

COMMENT ON COLUMN sample_order_line_items.product_description IS
    'What to make out of this product for this order — the "opis za radnike". '
    'Distinct from note, which is a remark about the line.';


-- === sample_order_line_items: the trigger that could never have run ==========

CREATE OR REPLACE FUNCTION public.set_sample_line_no() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  -- Was `new.line_no`. No such column exists; the column is order_line, and
  -- plpgsql only finds that out at insert time. See this migration's header.
  if new.order_line is null then
    select coalesce(max(order_line), 0) + 1
      into new.order_line
      from sample_order_line_items
     where sample_order_id = new.sample_order_id
       for update;
  end if;

  return new;
end;
$$;


-- === sample_order_line_items: one LIVE line per position =====================

ALTER TABLE sample_order_line_items
    DROP CONSTRAINT IF EXISTS uq_sample_order_line_items_order_line;

CREATE UNIQUE INDEX uq_sample_order_line_items_order_line_active
    ON sample_order_line_items (sample_order_id, order_line)
    WHERE is_active AND archived_at IS NULL;

COMMENT ON INDEX uq_sample_order_line_items_order_line_active IS
    'One live line per position. Archived revisions are history, not positions, '
    'so they are excluded — an edited order keeps its old lines beside the new.';


-- === sample_order_mailing_lists: which lists the order was sent from =========

CREATE TABLE sample_order_mailing_lists (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    sample_order_id BIGINT      NOT NULL,
    mailing_list_id BIGINT      NOT NULL,

    -- NOT NULL: choosing to mail a list of people is always somebody's act.
    added_by        BIGINT      NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_soml_sample_order
        FOREIGN KEY (sample_order_id) REFERENCES sample_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_soml_mailing_list
        FOREIGN KEY (mailing_list_id) REFERENCES mailing_lists (id),
    CONSTRAINT fk_soml_added_by
        FOREIGN KEY (added_by) REFERENCES users (id),

    CONSTRAINT uq_sample_order_mailing_lists UNIQUE (sample_order_id, mailing_list_id)
);

COMMENT ON TABLE sample_order_mailing_lists IS
    'Records that a list was SELECTED for a sample order. Intent only — the '
    'recipients it produced live in sample_order_recipients and are independent '
    'from that moment on.';


-- === sample_order_recipients: who the order actually mails ===================

CREATE TABLE sample_order_recipients (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    sample_order_id        BIGINT       NOT NULL,

    -- Present when the recipient has an account here. The address is snapshotted
    -- regardless, so history survives the person changing their e-mail.
    user_id                BIGINT,

    recipient_email        VARCHAR(320) NOT NULL,
    recipient_name         VARCHAR(150),

    source_type            VARCHAR(20)  NOT NULL,
    source_mailing_list_id BIGINT,

    -- NULL only for SYSTEM rows, which have no human author.
    added_by               BIGINT,

    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Removal is an archive, and is always attributable.
    removed_at             TIMESTAMPTZ,
    removed_by             BIGINT,

    CONSTRAINT fk_sor_sample_order
        FOREIGN KEY (sample_order_id) REFERENCES sample_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_sor_user            FOREIGN KEY (user_id)                REFERENCES users (id),
    CONSTRAINT fk_sor_mailing_list    FOREIGN KEY (source_mailing_list_id) REFERENCES mailing_lists (id),
    CONSTRAINT fk_sor_added_by        FOREIGN KEY (added_by)               REFERENCES users (id),
    CONSTRAINT fk_sor_removed_by      FOREIGN KEY (removed_by)             REFERENCES users (id),

    CONSTRAINT chk_sor_source_type
        CHECK (source_type IN ('MAILING_LIST', 'MANUAL', 'SYSTEM')),

    -- A MAILING_LIST row names its list; nothing else may.
    CONSTRAINT chk_sor_source_list_consistency
        CHECK ((source_type =  'MAILING_LIST' AND source_mailing_list_id IS NOT NULL)
            OR (source_type <> 'MAILING_LIST' AND source_mailing_list_id IS NULL)),

    CONSTRAINT chk_sor_added_by
        CHECK ((source_type =  'SYSTEM' AND added_by IS NULL)
            OR (source_type <> 'SYSTEM' AND added_by IS NOT NULL)),

    -- The whitespace ban is what blocks CR/LF SMTP header injection, so it is
    -- stated here and not only in the service.
    CONSTRAINT chk_sor_email
        CHECK (recipient_email = lower(btrim(recipient_email))
           AND recipient_email LIKE '%_@_%._%'
           AND recipient_email !~ '[[:space:]]'),

    CONSTRAINT chk_sor_name
        CHECK (recipient_name IS NULL OR length(btrim(recipient_name)) > 0),

    CONSTRAINT chk_sor_removal_state
        CHECK ((removed_at IS     NULL AND removed_by IS     NULL)
            OR (removed_at IS NOT NULL AND removed_by IS NOT NULL))
);

-- The send path and the snapshot view both ask only for live rows.
CREATE INDEX idx_sor_order_active
    ON sample_order_recipients (sample_order_id)
    WHERE removed_at IS NULL;

-- What detaching a list has to find.
CREATE INDEX idx_sor_source_mailing_list
    ON sample_order_recipients (sample_order_id, source_mailing_list_id)
    WHERE source_mailing_list_id IS NOT NULL AND removed_at IS NULL;

-- One live row per address per order. The service checks first for a clean
-- error message; THIS is what makes it true when two attaches race.
CREATE UNIQUE INDEX uq_sor_order_email_active
    ON sample_order_recipients (sample_order_id, lower(recipient_email))
    WHERE removed_at IS NULL;

COMMENT ON TABLE sample_order_recipients IS
    'One address a sample order is (or was) sent to. A SNAPSHOT: once written it '
    'stops following the list it came from, which is what makes "who was this '
    'sent to" answerable a year later.';


-- === sample_order_email_threads: one conversation per order ==================

CREATE TABLE sample_order_email_threads (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    sample_order_id  BIGINT       NOT NULL,

    -- Without "Re:", and never edited afterwards: clients weigh the subject
    -- alongside References when grouping, so an edited subject can split a
    -- conversation whose headers are otherwise perfect.
    subject_base     VARCHAR(255) NOT NULL,

    root_message_id  VARCHAR(255) NOT NULL,
    last_message_id  VARCHAR(255) NOT NULL,

    references_chain TEXT         NOT NULL DEFAULT '',
    message_count    INTEGER      NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,

    CONSTRAINT fk_soet_sample_order
        FOREIGN KEY (sample_order_id) REFERENCES sample_orders (id),

    CONSTRAINT uq_soet_sample_order UNIQUE (sample_order_id),

    CONSTRAINT chk_soet_message_count CHECK (message_count >= 0)
);

COMMENT ON TABLE sample_order_email_threads IS
    'One e-mail conversation per sample order: frozen subject plus the '
    'Message-ID chain later mails attach to. See V14 for the reasoning.';


-- === manufacturing_time_requests: a sample line can be the occasion too ======

-- Exists only to be the composite foreign key's target, exactly as V3 added the
-- production-order one. `id` is already the primary key, so this forbids nothing
-- new.
ALTER TABLE sample_order_line_items
    ADD CONSTRAINT uq_sample_order_line_items_id_product UNIQUE (id, product_id);

ALTER TABLE manufacturing_time_requests
    ADD COLUMN sample_order_line_item_id BIGINT;

-- MATCH SIMPLE (the default) is deliberate: with the column NULL the constraint
-- is not checked at all, which is precisely a request raised on its own.
ALTER TABLE manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_sample_line_item
        FOREIGN KEY (sample_order_line_item_id, product_id)
        REFERENCES sample_order_line_items (id, product_id)
        ON DELETE RESTRICT;

-- A request has ONE occasion, or none. Both at once would make
-- "which order is waiting on this" a question with two answers.
ALTER TABLE manufacturing_time_requests
    ADD CONSTRAINT chk_manufacturing_time_requests_single_occasion
        CHECK (production_order_line_item_id IS NULL
            OR sample_order_line_item_id IS NULL);

-- Partial: most requests are standalone, and the only question ever asked of
-- this column is "which requests belong to this line".
CREATE INDEX idx_manufacturing_time_requests_sample_line_item
    ON manufacturing_time_requests (sample_order_line_item_id)
    WHERE sample_order_line_item_id IS NOT NULL;

COMMENT ON COLUMN manufacturing_time_requests.sample_order_line_item_id IS
    'The sample-order line the request was raised on. The line is the OCCASION; '
    'what the request is about is always product_id. Mutually exclusive with '
    'production_order_line_item_id.';


-- === audit trail: the new tables are audited like the ones they mirror =======
-- audit_trigger_fn resolves the table by NAME against audit_tables and writes
-- the id it finds into audit_logs.table_id, which is NOT NULL. An unregistered
-- table therefore does not merely go unaudited — the first insert into it fails.
-- So the registration comes first.

INSERT INTO audit_tables (table_name)
SELECT t.name
  FROM (VALUES ('sample_order_recipients'),
               ('sample_order_mailing_lists')) AS t(name)
 WHERE NOT EXISTS (
     SELECT 1 FROM audit_tables a WHERE a.table_name = t.name
 );

CREATE TRIGGER trg_audit_logs_sample_order_recipients
    AFTER INSERT OR DELETE OR UPDATE ON sample_order_recipients
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

CREATE TRIGGER trg_audit_logs_sample_order_mailing_lists
    AFTER INSERT OR DELETE OR UPDATE ON sample_order_mailing_lists
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

CREATE TRIGGER trg_03_sample_order_email_threads_updated_at
    BEFORE UPDATE ON sample_order_email_threads
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
