-- =============================================================================
-- What a production order actually asks of the floor
-- =============================================================================
-- WHAT CHANGES
--   Three new tables, nothing else:
--     · production_order_scope_requests            — the request itself
--     · production_order_scope_request_items       — one order line it covers
--     · production_order_scope_request_operations  — the decided operations
--
-- WHY AT ALL
--   The database can say which operations a PRODUCT has, and how long one piece
--   takes (product_manufacturing_times). It cannot say which operations THIS
--   ORDER actually needs: a line may be a variant that skips two operations, or
--   one that carries three of a part where the catalogue says one. Until that is
--   written down, "how much of this order is done" has no denominator — the work
--   recorded in work_logs can be counted, but never against anything.
--
--   So: commercial staff ask (a request), the supervisor answers by going
--   through the product's operations line by line and saying which are needed
--   and in what quantity per assembly. That answer is this schema.
--
-- WHY IT MIRRORS manufacturing_time_requests
--   Same workflow, same rules: owned before decided, terminal statuses, a
--   version column so two supervisors cannot both decide it. The check
--   constraints are deliberately the same shape, so the two request tables read
--   as one idea rather than two.
--
-- WHY THE RESULT IS A SNAPSHOT
--   operation_name and units_per_product_snapshot are copied at the moment the
--   supervisor fills the modal. Renaming an operation or changing its
--   units_per_product afterwards must not silently rewrite an order's agreed
--   scope — the same reason product_manufacturing_time_operations snapshots.
--
-- WHY result_state AND status BOTH EXIST
--   The supervisor may SAVE without submitting: the request stays IN_REVIEW,
--   the answer exists as a DRAFT and is theirs to edit. Submitting is what
--   completes the request. One column cannot hold both facts, and the check
--   constraint below ties them together so no writer can produce a COMPLETED
--   request with an unsubmitted answer.
--
-- MIGRATION IMPACT
--   · Additive only. No existing table, column, constraint, trigger, function
--     or row is altered. Nothing that runs today can break.
--   · All three tables are registered for auditing, so a changed scope carries
--     who changed it from the first write.
--   · set_updated_at() and audit_trigger_fn() already exist and are reused, not
--     redefined.
--   · Every step is guarded, so replaying the migration is safe.
--   · Rollback:
--       DROP TABLE public.production_order_scope_request_operations;
--       DROP TABLE public.production_order_scope_request_items;
--       DROP TABLE public.production_order_scope_requests;
--       DELETE FROM public.audit_tables
--        WHERE table_name IN ('production_order_scope_requests',
--                             'production_order_scope_request_items',
--                             'production_order_scope_request_operations');
--     Nothing else depends on any of it.
-- =============================================================================


-- ── The request ──────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.production_order_scope_requests (
    id                  bigint GENERATED ALWAYS AS IDENTITY,

    -- The order the request is about. Always present: unlike a manufacturing
    -- time, which hangs off a product, an order's scope has no meaning without
    -- the order.
    production_order_id bigint        NOT NULL,

    -- Whether the requester asked about the whole order or about one line. Both
    -- are stored the same way -- as items below -- so the workflow has one
    -- shape; this column records what was ASKED, which the item count cannot
    -- (an order with a single line makes the two indistinguishable).
    scope               character varying(20) NOT NULL,

    created_by          bigint        NOT NULL,
    status              character varying(20) NOT NULL DEFAULT 'PENDING',
    assigned_to         bigint,
    processed_by        bigint,
    processed_at        timestamp with time zone,
    decision_note       character varying(2000),

    -- NULL  — the supervisor has not saved anything yet
    -- DRAFT — saved, still theirs to edit, request still IN_REVIEW
    -- SUBMITTED — handed over; the request is COMPLETED and the answer is read-only
    result_state        character varying(20),

    cancelled_at        timestamp with time zone,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone,

    -- Optimistic locking, as on manufacturing_time_requests: two supervisors
    -- must not both submit the same answer. The loser gets a 409.
    version             bigint        NOT NULL DEFAULT 0,

    CONSTRAINT pk_production_order_scope_requests PRIMARY KEY (id),

    CONSTRAINT fk_po_scope_requests_order
        FOREIGN KEY (production_order_id)
        REFERENCES public.production_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_scope_requests_created_by
        FOREIGN KEY (created_by) REFERENCES public.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_scope_requests_assigned_to
        FOREIGN KEY (assigned_to) REFERENCES public.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_scope_requests_processed_by
        FOREIGN KEY (processed_by) REFERENCES public.users (id) ON DELETE RESTRICT,

    CONSTRAINT chk_po_scope_requests_status
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'COMPLETED', 'DECLINED', 'CANCELLED')),

    CONSTRAINT chk_po_scope_requests_scope
        CHECK (scope IN ('ORDER', 'LINE_ITEM')),

    CONSTRAINT chk_po_scope_requests_decision_note
        CHECK (decision_note IS NULL OR length(btrim(decision_note)) > 0),

    -- A finished request records who finished it and when; an unfinished one
    -- never does. Withdrawal is timestamped separately and is not a "processing"
    -- outcome. Same rule, same shape as manufacturing_time_requests.
    CONSTRAINT chk_po_scope_requests_processing_state
        CHECK (
            (status IN ('PENDING', 'IN_REVIEW')
                AND processed_by IS NULL AND processed_at IS NULL AND cancelled_at IS NULL)
            OR (status IN ('COMPLETED', 'DECLINED')
                AND processed_by IS NOT NULL AND processed_at IS NOT NULL AND cancelled_at IS NULL)
            OR (status = 'CANCELLED'
                AND cancelled_at IS NOT NULL AND processed_by IS NULL AND processed_at IS NULL)
        ),

    -- IN_REVIEW means somebody owns it. PENDING means nobody does.
    CONSTRAINT chk_po_scope_requests_assignment_state
        CHECK (
            (status = 'IN_REVIEW' AND assigned_to IS NOT NULL)
            OR (status = 'PENDING' AND assigned_to IS NULL)
            OR status IN ('COMPLETED', 'DECLINED', 'CANCELLED')
        ),

    -- "Completed" and "submitted" are the same fact, stated in two columns, so
    -- the database is what keeps them equal. A DECLINED or CANCELLED request may
    -- still carry the draft the supervisor had saved before deciding otherwise --
    -- that is history, not an answer, and deleting it would lose why.
    CONSTRAINT chk_po_scope_requests_result_state
        CHECK (
            (status = 'COMPLETED' AND result_state = 'SUBMITTED')
            OR (status <> 'COMPLETED'
                AND (result_state IS NULL OR result_state = 'DRAFT'))
        )
);

-- The supervisor's queue: everything still open, oldest first.
CREATE INDEX IF NOT EXISTS idx_po_scope_requests_open
    ON public.production_order_scope_requests (created_at)
    WHERE status IN ('PENDING', 'IN_REVIEW');

-- "every scope request on this order"
CREATE INDEX IF NOT EXISTS idx_po_scope_requests_order
    ON public.production_order_scope_requests (production_order_id);

-- "my requests", newest first
CREATE INDEX IF NOT EXISTS idx_po_scope_requests_created_by
    ON public.production_order_scope_requests (created_by, created_at DESC);

-- "requests assigned to me" -- partial, most rows are unassigned.
CREATE INDEX IF NOT EXISTS idx_po_scope_requests_assigned_to
    ON public.production_order_scope_requests (assigned_to)
    WHERE assigned_to IS NOT NULL;

-- The default list view: filter by status, newest first.
CREATE INDEX IF NOT EXISTS idx_po_scope_requests_status_created
    ON public.production_order_scope_requests (status, created_at DESC);


-- ── The lines a request covers ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.production_order_scope_request_items (
    id                           bigint GENERATED ALWAYS AS IDENTITY,

    request_id                   bigint NOT NULL,
    production_order_line_item_id bigint NOT NULL,

    -- What the REQUESTER wrote about this line. Prefilled from the line's own
    -- notes and then edited freely, so it is stored here rather than read back
    -- from the line: the request must keep saying what was asked even after the
    -- order's notes are changed.
    note                         character varying(2000),

    -- The line's own position in the order, copied so the answer reads in the
    -- same order as the order does without joining back for a sort.
    line_order                   integer NOT NULL DEFAULT 1,

    created_at                   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                   timestamp with time zone,

    CONSTRAINT pk_po_scope_request_items PRIMARY KEY (id),

    -- CASCADE, unlike every reference in this schema, because an item is PART OF
    -- the request rather than a thing that points at one. Requests are never
    -- deleted; this says what would be true if one ever were.
    CONSTRAINT fk_po_scope_request_items_request
        FOREIGN KEY (request_id)
        REFERENCES public.production_order_scope_requests (id) ON DELETE CASCADE,

    CONSTRAINT fk_po_scope_request_items_line_item
        FOREIGN KEY (production_order_line_item_id)
        REFERENCES public.production_order_line_items (id) ON DELETE RESTRICT,

    -- One request never covers the same line twice.
    CONSTRAINT uq_po_scope_request_items_request_line
        UNIQUE (request_id, production_order_line_item_id),

    CONSTRAINT chk_po_scope_request_items_note
        CHECK (note IS NULL OR length(btrim(note)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_po_scope_request_items_request
    ON public.production_order_scope_request_items (request_id, line_order);

-- "is there a request covering this line?"
CREATE INDEX IF NOT EXISTS idx_po_scope_request_items_line_item
    ON public.production_order_scope_request_items (production_order_line_item_id);


-- ── The answer: which operations, and how many per assembly ──────────────────

CREATE TABLE IF NOT EXISTS public.production_order_scope_request_operations (
    id                        bigint GENERATED ALWAYS AS IDENTITY,

    request_item_id           bigint NOT NULL,
    operation_id              bigint NOT NULL,

    -- Snapshot. A later rename must not change what an order agreed to.
    operation_name            character varying(255) NOT NULL,

    -- FALSE is the supervisor saying "this variant does not need it". The row
    -- stays, so the answer records the decision rather than an absence somebody
    -- has to interpret.
    needed                    boolean NOT NULL DEFAULT true,

    -- What the catalogue said when the modal was filled, and what the supervisor
    -- decided. Both are kept: the pair is what shows an override was deliberate.
    units_per_product_snapshot integer,
    units_per_product_value    integer,

    line_order                integer NOT NULL DEFAULT 1,

    created_at                timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                timestamp with time zone,

    CONSTRAINT pk_po_scope_request_operations PRIMARY KEY (id),

    -- Part of the item, same reasoning as the item is part of the request.
    CONSTRAINT fk_po_scope_request_operations_item
        FOREIGN KEY (request_item_id)
        REFERENCES public.production_order_scope_request_items (id) ON DELETE CASCADE,

    CONSTRAINT fk_po_scope_request_operations_operation
        FOREIGN KEY (operation_id)
        REFERENCES public.operations (id) ON DELETE RESTRICT,

    CONSTRAINT uq_po_scope_request_operations_item_operation
        UNIQUE (request_item_id, operation_id),

    -- Zero pieces per assembly is not a quantity, it is "not needed" -- which
    -- has its own column and must be said there.
    CONSTRAINT chk_po_scope_request_operations_units_positive
        CHECK (units_per_product_value IS NULL OR units_per_product_value > 0),

    -- A needed operation always says how many. Without it the order's scope has
    -- a hole exactly where the count was supposed to come from.
    CONSTRAINT chk_po_scope_request_operations_units_required
        CHECK (needed = false OR units_per_product_value IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_po_scope_request_operations_item
    ON public.production_order_scope_request_operations (request_item_id, line_order);

-- "everything the floor agreed to do with this operation"
CREATE INDEX IF NOT EXISTS idx_po_scope_request_operations_operation
    ON public.production_order_scope_request_operations (operation_id);


-- ── updated_at ───────────────────────────────────────────────────────────────
-- set_updated_at() already exists and about thirty tables run it. It bumps
-- updated_at only when a column other than updated_at actually changed.

DROP TRIGGER IF EXISTS trg_03_po_scope_requests_updated_at
    ON public.production_order_scope_requests;
CREATE TRIGGER trg_03_po_scope_requests_updated_at
    BEFORE UPDATE ON public.production_order_scope_requests
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS trg_03_po_scope_request_items_updated_at
    ON public.production_order_scope_request_items;
CREATE TRIGGER trg_03_po_scope_request_items_updated_at
    BEFORE UPDATE ON public.production_order_scope_request_items
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS trg_03_po_scope_request_operations_updated_at
    ON public.production_order_scope_request_operations;
CREATE TRIGGER trg_03_po_scope_request_operations_updated_at
    BEFORE UPDATE ON public.production_order_scope_request_operations
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


-- ── Auditing ─────────────────────────────────────────────────────────────────
-- audit_trigger_fn resolves the table by NAME against audit_tables, so the
-- registration has to exist before the trigger can record anything.

INSERT INTO public.audit_tables (table_name)
SELECT t.name
  FROM (VALUES ('production_order_scope_requests'),
               ('production_order_scope_request_items'),
               ('production_order_scope_request_operations')) AS t(name)
 WHERE NOT EXISTS (
     SELECT 1 FROM public.audit_tables a WHERE a.table_name = t.name
 );

DROP TRIGGER IF EXISTS trg_audit_logs_po_scope_requests
    ON public.production_order_scope_requests;
CREATE TRIGGER trg_audit_logs_po_scope_requests
    AFTER INSERT OR DELETE OR UPDATE ON public.production_order_scope_requests
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_po_scope_request_items
    ON public.production_order_scope_request_items;
CREATE TRIGGER trg_audit_logs_po_scope_request_items
    AFTER INSERT OR DELETE OR UPDATE ON public.production_order_scope_request_items
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_po_scope_request_operations
    ON public.production_order_scope_request_operations;
CREATE TRIGGER trg_audit_logs_po_scope_request_operations
    AFTER INSERT OR DELETE OR UPDATE ON public.production_order_scope_request_operations
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


-- ── What the tables mean ─────────────────────────────────────────────────────

COMMENT ON TABLE public.production_order_scope_requests IS
    'A request for the floor to say which operations a production order actually needs. Append-only history; never deleted.';
COMMENT ON COLUMN public.production_order_scope_requests.scope IS
    'What was asked: ORDER (every line) or LINE_ITEM (one). Both are stored as items; this records the question.';
COMMENT ON COLUMN public.production_order_scope_requests.result_state IS
    'NULL = nothing saved yet, DRAFT = saved and still editable, SUBMITTED = handed over. SUBMITTED and status COMPLETED are the same fact.';
COMMENT ON TABLE public.production_order_scope_request_items IS
    'One production-order line a scope request covers, with the note the requester wrote about it.';
COMMENT ON TABLE public.production_order_scope_request_operations IS
    'The decided scope of one line: which of the product operations are needed and how many per assembly. Snapshotted, so later catalogue edits do not rewrite it.';
