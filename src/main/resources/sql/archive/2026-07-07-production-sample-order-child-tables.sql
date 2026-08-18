-- Captures the production_order / sample_order schema changes made directly against
-- the live DB (see backup 07_07_2026_backup) so they are reproducible on any other
-- environment. Every statement here is idempotent — safe to re-run against the
-- current live DB (no-op) or against a fresh DB that only has the pre-existing
-- production_orders / production_order_line_items / sample_orders /
-- sample_order_line_items base tables (created via an earlier, untracked baseline
-- or by ddl-auto=update from the JPA entities).
--
-- Design note: production_order_deadlines, production_order_line_item_quantities,
-- production_order_line_item_notes, sample_order_line_item_quantities, and
-- sample_order_line_item_notes are versioned-history child tables (one row per
-- revision of a deadline/quantity/note, ordered by the order_* index column, with
-- is_active marking the current revision and archived_at requiring is_active=false).
-- No versioning workflow logic lives here — only the schema shape.

BEGIN;

-- === production_orders: order_date (distinct from delivery_deadline) ===
ALTER TABLE production_orders
    ADD COLUMN IF NOT EXISTS order_date date;

-- === production_order_line_items: link to a specific product ===
ALTER TABLE production_order_line_items
    ADD COLUMN IF NOT EXISTS product_id bigint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS product_description text,
    ADD COLUMN IF NOT EXISTS line_order integer NOT NULL DEFAULT 1;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_production_order_line_items_product_id') THEN
        ALTER TABLE production_order_line_items
            ADD CONSTRAINT fk_production_order_line_items_product_id
            FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_production_order_line_items_product_id
    ON production_order_line_items (product_id);

-- === sample_orders: workflow status + who closed it ===
ALTER TABLE sample_orders
    ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'created',
    ADD COLUMN IF NOT EXISTS closed_by bigint;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sample_orders_closed_by') THEN
        ALTER TABLE sample_orders
            ADD CONSTRAINT fk_sample_orders_closed_by
            FOREIGN KEY (closed_by) REFERENCES users(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sample_orders_closed_by ON sample_orders (closed_by);

-- === sample_order_line_items: one line per order_line number, catalog_no unique when set ===
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_sample_order_line_items_order_line') THEN
        ALTER TABLE sample_order_line_items
            ADD CONSTRAINT uq_sample_order_line_items_order_line
            UNIQUE (sample_order_id, order_line);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sample_order_line_items_catalog_number
    ON sample_order_line_items (catalog_no) WHERE (catalog_no IS NOT NULL);

-- === production_order_deadlines: versioned deadline-date history per order ===
CREATE TABLE IF NOT EXISTS production_order_deadlines (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_id    BIGINT NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
    deadline_order          INTEGER NOT NULL DEFAULT 1,
    deadline_date_from       DATE,
    deadline_date_to          DATE NOT NULL,
    quantity                    INTEGER,
    is_active                    BOOLEAN NOT NULL DEFAULT true,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ,
    archived_at                   TIMESTAMPTZ,
    CONSTRAINT chk_production_order_deadlines_archived_implies_inactive
        CHECK (archived_at IS NULL OR is_active = false),
    CONSTRAINT chk_production_order_deadlines_date_range
        CHECK (deadline_date_from IS NULL OR deadline_date_from <= deadline_date_to)
);
CREATE INDEX IF NOT EXISTS idx_production_order_deadlines_production_order_id
    ON production_order_deadlines (production_order_id);

-- === production_order_line_item_quantities: versioned quantity history per line item ===
CREATE TABLE IF NOT EXISTS production_order_line_item_quantities (
    id                                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_line_item_id     BIGINT NOT NULL REFERENCES production_order_line_items(id) ON DELETE CASCADE,
    order_quantity                     INTEGER NOT NULL DEFAULT 1,
    quantity                            INTEGER NOT NULL DEFAULT 0,
    delivery_deadline                    DATE,
    is_active                             BOOLEAN NOT NULL DEFAULT true,
    created_at                             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                              TIMESTAMPTZ,
    archived_at                              TIMESTAMPTZ,
    CONSTRAINT chk_production_order_line_item_quantities_archived_implies_inac
        CHECK (archived_at IS NULL OR is_active = false)
);
CREATE INDEX IF NOT EXISTS idx_production_order_line_item_quantities_production_order_line
    ON production_order_line_item_quantities (production_order_line_item_id);

-- === production_order_line_item_notes: versioned note history per line item ===
CREATE TABLE IF NOT EXISTS production_order_line_item_notes (
    id                                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_order_line_item_id     BIGINT NOT NULL REFERENCES production_order_line_items(id) ON DELETE CASCADE,
    order_note                         INTEGER NOT NULL DEFAULT 1,
    note                                 TEXT NOT NULL DEFAULT '',
    is_active                            BOOLEAN NOT NULL DEFAULT true,
    created_at                            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                             TIMESTAMPTZ,
    archived_at                             TIMESTAMPTZ,
    CONSTRAINT chk_production_order_line_item_notes_archived_implies_inactive
        CHECK (archived_at IS NULL OR is_active = false)
);
CREATE INDEX IF NOT EXISTS idx_production_order_line_item_notes_production_order_line_item
    ON production_order_line_item_notes (production_order_line_item_id);

-- === sample_order_line_item_quantities: versioned quantity history per line item ===
CREATE TABLE IF NOT EXISTS sample_order_line_item_quantities (
    id                            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_order_line_item_id     BIGINT NOT NULL REFERENCES sample_order_line_items(id) ON DELETE CASCADE,
    order_quantity                 INTEGER NOT NULL DEFAULT 1,
    quantity                        INTEGER NOT NULL DEFAULT 0,
    is_active                        BOOLEAN NOT NULL DEFAULT true,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                         TIMESTAMPTZ,
    archived_at                         TIMESTAMPTZ,
    CONSTRAINT chk_sample_order_line_item_quantities_archived_implies_inactive
        CHECK (archived_at IS NULL OR is_active = false)
);
CREATE INDEX IF NOT EXISTS idx_sample_order_line_item_quantities_sample_order_line_item_id
    ON sample_order_line_item_quantities (sample_order_line_item_id);

-- === sample_order_line_item_notes: versioned note history per line item ===
-- The revision-index column is genuinely named order_quantity on the live DB (not
-- order_note, unlike the production_order counterpart above) — kept as-is here to
-- match what already exists; not a typo introduced by this migration.
CREATE TABLE IF NOT EXISTS sample_order_line_item_notes (
    id                            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_order_line_item_id     BIGINT NOT NULL REFERENCES sample_order_line_items(id) ON DELETE CASCADE,
    order_quantity                 INTEGER NOT NULL DEFAULT 1,
    note                            TEXT NOT NULL DEFAULT '',
    is_active                        BOOLEAN NOT NULL DEFAULT true,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                         TIMESTAMPTZ,
    archived_at                         TIMESTAMPTZ,
    CONSTRAINT chk_sample_order_line_item_notes_archived_implies_inactive
        CHECK (archived_at IS NULL OR is_active = false)
);
CREATE INDEX IF NOT EXISTS idx_sample_order_line_item_notes_sample_order_line_item_id
    ON sample_order_line_item_notes (sample_order_line_item_id);

COMMIT;
