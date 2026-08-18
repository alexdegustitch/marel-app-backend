-- =============================================================================
-- Manufacturing-time requests
-- =============================================================================
-- The database already carried two stub tables from the original schema
-- bootstrap:
--
--   requests(id, user_id, is_active, created_at, updated_at, archived_at)
--   manufacturing_time_requests(id, request_id, production_order_id)
--
-- Neither had a JPA entity, repository, service, controller or any frontend
-- caller — verified by searching both repositories. Their only rows were demo
-- seed data inserted in one batch on 2026-01-08 15:29 (6 contentless `requests`
-- rows, 79 link rows). They carried no status, type, description, assignee or
-- decision, so they could not express the workflow at all.
--
-- Decision: the stubs are dropped and the audited name
-- `manufacturing_time_requests` is reused for the real workflow table, rather
-- than leaving a dead generic `requests` concept next to a competing new one.
--
-- Two deviations from the incoming specification, both driven by verified
-- repository behaviour:
--
--   1. The stub modelled a request against MANY production orders. The real
--      manufacturing-time domain (product_manufacturing_times) hangs off a
--      PRODUCT, so the new table references product_id, as specified. The
--      production-order association of the seed rows carried no business
--      meaning and is not reproduced.
--
--   2. Request type DELETE is NOT offered. ProductManufacturingTimeService.delete()
--      is a soft delete (is_active = false); the domain has no physical delete.
--      The type is therefore DEACTIVATE, which is what the system can actually do.
--
-- Existing audit_logs rows for the stub tables are deliberately preserved. The
-- audit_tables entries are renamed to *_legacy so historical rows keep pointing
-- at the retired table they actually describe, and the new table gets a fresh
-- entry.
-- =============================================================================

-- The stub is identified by its shape (a request_id column), never by name
-- alone. Re-running this script after the real table exists must NOT drop it —
-- the real table has no request_id, so the guard fails closed.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'manufacturing_time_requests' AND column_name = 'request_id'
    ) THEN
        DROP TABLE manufacturing_time_requests;

        UPDATE audit_tables SET table_name = 'manufacturing_time_requests_legacy'
        WHERE table_name = 'manufacturing_time_requests';
    END IF;

    -- Same reasoning: only the contentless stub (no status column) is dropped.
    IF EXISTS (
        SELECT 1 FROM information_schema.tables WHERE table_name = 'requests'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'requests' AND column_name = 'status'
    ) THEN
        DROP TABLE requests;

        UPDATE audit_tables SET table_name = 'requests_legacy'
        WHERE table_name = 'requests';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS manufacturing_time_requests (
    id                           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id                   BIGINT       NOT NULL,
    created_by                   BIGINT       NOT NULL,
    request_type                 VARCHAR(20)  NOT NULL,
    description                  VARCHAR(2000) NOT NULL,
    status                       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    assigned_to                  BIGINT,
    processed_by                 BIGINT,
    processed_at                 TIMESTAMPTZ,
    decision_note                VARCHAR(2000),
    -- The manufacturing-time record the request acts ON (update / recalculate /
    -- deactivate). NULL for CREATE.
    target_manufacturing_time_id BIGINT,
    cancelled_at                 TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ,
    -- Optimistic locking: two processors must not both complete/decline the
    -- same request. The loser fails with a 409 instead of silently overwriting.
    version                      BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_manufacturing_time_requests_product_id
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_manufacturing_time_requests_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_manufacturing_time_requests_assigned_to
        FOREIGN KEY (assigned_to) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_manufacturing_time_requests_processed_by
        FOREIGN KEY (processed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_manufacturing_time_requests_target
        FOREIGN KEY (target_manufacturing_time_id)
        REFERENCES product_manufacturing_times (id) ON DELETE RESTRICT,

    CONSTRAINT chk_manufacturing_time_requests_status
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'COMPLETED', 'DECLINED', 'CANCELLED')),

    CONSTRAINT chk_manufacturing_time_requests_type
        CHECK (request_type IN ('CREATE', 'UPDATE', 'RECALCULATE', 'DEACTIVATE')),

    CONSTRAINT chk_manufacturing_time_requests_description
        CHECK (length(trim(description)) > 0),

    CONSTRAINT chk_manufacturing_time_requests_decision_note
        CHECK (decision_note IS NULL OR length(trim(decision_note)) > 0),

    -- Every type except CREATE acts on an existing record, so it must name one.
    -- CREATE has nothing to target yet.
    CONSTRAINT chk_manufacturing_time_requests_target_required
        CHECK (
            (request_type = 'CREATE' AND target_manufacturing_time_id IS NULL)
            OR (request_type <> 'CREATE' AND target_manufacturing_time_id IS NOT NULL)
        ),

    -- A finished request always records who finished it and when; an unfinished
    -- one never does. Cancellation is timestamped separately and is not a
    -- "processing" outcome.
    CONSTRAINT chk_manufacturing_time_requests_processing_state
        CHECK (
            (status IN ('PENDING', 'IN_REVIEW')
                AND processed_by IS NULL AND processed_at IS NULL AND cancelled_at IS NULL)
            OR (status IN ('COMPLETED', 'DECLINED')
                AND processed_by IS NOT NULL AND processed_at IS NOT NULL AND cancelled_at IS NULL)
            OR (status = 'CANCELLED'
                AND cancelled_at IS NOT NULL AND processed_by IS NULL AND processed_at IS NULL)
        ),

    -- IN_REVIEW means somebody owns it. PENDING means nobody does.
    CONSTRAINT chk_manufacturing_time_requests_assignment_state
        CHECK (
            (status = 'IN_REVIEW' AND assigned_to IS NOT NULL)
            OR (status = 'PENDING' AND assigned_to IS NULL)
            OR status IN ('COMPLETED', 'DECLINED', 'CANCELLED')
        )
);

-- The processor's work queue: everything still open, oldest first.
CREATE INDEX IF NOT EXISTS idx_manufacturing_time_requests_open
    ON manufacturing_time_requests (created_at)
    WHERE status IN ('PENDING', 'IN_REVIEW');

-- "all requests for this product"
CREATE INDEX IF NOT EXISTS idx_manufacturing_time_requests_product_id
    ON manufacturing_time_requests (product_id);

-- "my submitted requests", newest first
CREATE INDEX IF NOT EXISTS idx_manufacturing_time_requests_created_by
    ON manufacturing_time_requests (created_by, created_at DESC);

-- "requests assigned to me" — partial, most rows are unassigned.
CREATE INDEX IF NOT EXISTS idx_manufacturing_time_requests_assigned_to
    ON manufacturing_time_requests (assigned_to)
    WHERE assigned_to IS NOT NULL;

-- Default admin list view: filter by status, newest first.
CREATE INDEX IF NOT EXISTS idx_manufacturing_time_requests_status_created
    ON manufacturing_time_requests (status, created_at DESC);

-- "is there an open request against this manufacturing-time record?"
CREATE INDEX IF NOT EXISTS idx_manufacturing_time_requests_target
    ON manufacturing_time_requests (target_manufacturing_time_id)
    WHERE target_manufacturing_time_id IS NOT NULL;

DROP TRIGGER IF EXISTS trg_03_manufacturing_time_requests_updated_at ON manufacturing_time_requests;
CREATE TRIGGER trg_03_manufacturing_time_requests_updated_at
    BEFORE UPDATE ON manufacturing_time_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE manufacturing_time_requests IS
    'A user request to create, update, recalculate or deactivate a product manufacturing time. Append-only history; never deleted.';
COMMENT ON COLUMN manufacturing_time_requests.created_by IS 'Who submitted the request.';
COMMENT ON COLUMN manufacturing_time_requests.assigned_to IS 'Who currently owns the request (set when it moves to IN_REVIEW).';
COMMENT ON COLUMN manufacturing_time_requests.processed_by IS 'Who completed or declined it. Business state, not audit metadata.';
COMMENT ON COLUMN manufacturing_time_requests.target_manufacturing_time_id IS
    'The existing manufacturing-time record the request acts on. NULL for CREATE.';


-- =============================================================================
-- Link the produced manufacturing-time record back to its request
-- =============================================================================
-- Cardinality: ONE request produces AT MOST ONE product_manufacturing_times row.
-- A CREATE request inserts one row; UPDATE / RECALCULATE / DEACTIVATE mutate the
-- targeted row and re-stamp source_request_id onto it. The column therefore
-- means "the request that most recently produced the current state of this
-- record" — the full chain of earlier requests lives in audit_logs (see the
-- audit trigger added below). Operation-level rows
-- (product_manufacturing_time_operations) hang off the parent record and are not
-- separately linked.
ALTER TABLE product_manufacturing_times
    ADD COLUMN IF NOT EXISTS source_request_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_pmt_source_request_id'
    ) THEN
        ALTER TABLE product_manufacturing_times
            ADD CONSTRAINT fk_pmt_source_request_id
            FOREIGN KEY (source_request_id)
            REFERENCES manufacturing_time_requests (id) ON DELETE RESTRICT;
    END IF;
END $$;

-- One request never yields two manufacturing-time records.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pmt_source_request_id
    ON product_manufacturing_times (source_request_id)
    WHERE source_request_id IS NOT NULL;

COMMENT ON COLUMN product_manufacturing_times.source_request_id IS
    'The manufacturing_time_requests row that most recently produced the current state of this record. NULL when created directly.';

-- product_manufacturing_times was never audited: audit_tables carried a stale
-- entry named "manufacturing_product_times" that matches no real table, and the
-- table had no audit trigger. Manufacturing-time creation and change must be
-- audit-friendly, so register the real name and attach the standard trigger.
INSERT INTO audit_tables (table_name)
SELECT 'product_manufacturing_times'
WHERE NOT EXISTS (
    SELECT 1 FROM audit_tables WHERE table_name = 'product_manufacturing_times'
);

DROP TRIGGER IF EXISTS trg_audit_logs_product_manufacturing_times ON product_manufacturing_times;
CREATE TRIGGER trg_audit_logs_product_manufacturing_times
    AFTER INSERT OR UPDATE OR DELETE ON product_manufacturing_times
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

INSERT INTO audit_tables (table_name)
SELECT 'manufacturing_time_requests'
WHERE NOT EXISTS (
    SELECT 1 FROM audit_tables WHERE table_name = 'manufacturing_time_requests'
);

DROP TRIGGER IF EXISTS trg_audit_logs_manufacturing_time_requests ON manufacturing_time_requests;
CREATE TRIGGER trg_audit_logs_manufacturing_time_requests
    AFTER INSERT OR UPDATE OR DELETE ON manufacturing_time_requests
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
