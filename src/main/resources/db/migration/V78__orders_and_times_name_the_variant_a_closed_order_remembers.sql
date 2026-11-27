-- =============================================================================
-- Orders and times name the variant; a closed order remembers its result
-- =============================================================================
-- WHAT CHANGES
--   Four nullable columns, no table rewrite, no backfill:
--
--       production_order_line_items.product_variant_id   bigint
--       sample_order_line_items.product_variant_id       bigint
--       product_manufacturing_times.product_variant_id   bigint
--       production_orders.closed_progress_snapshot       jsonb
--
-- WHY
--   · ORDER LINES: a product that comes in variants is ordered AS a variant —
--     a different variant is a different line (SPEC odluka 10). The composite
--     FK onto product_variants (id, product_id) makes "the variant belongs to
--     THIS line's product" a database fact. NULL remains what it is today: a
--     product without variants. The same variant may appear on any number of
--     lines — deliberately no unique constraint.
--   · MANUFACTURING TIMES: a time for a product with variants is really the
--     time of ONE variant. NULL on such a product is ALSO legal (SPEC odluka
--     9): the configurator then opens the union of every variant's operations
--     and the existing excluded mechanism picks what counts.
--   · CLOSED ORDERS (SPEC odluka 16): progress is a live metric — it is
--     recomputed from current bindings, units and groups on every read. For a
--     DELIVERED or CANCELLED order that is a bug, not a feature: an order that
--     closed at 100 % must not drift to 80 % because somebody later edited
--     units_per_product. At the moment of closing, both measures (work
--     percent, finished-goods range, per line) are computed one last time and
--     written here, in the SAME transaction as the status; every later read
--     returns the snapshot. Reopening clears it. A pre-existing closed order
--     (NULL snapshot) is computed and saved on first read — lazy backfill with
--     the same code and structure. The JSON carries schemaVersion and
--     calculatedAt so a later shape can tell the old one apart.
--
-- ROLLBACK
--   ALTER TABLE production_orders           DROP COLUMN closed_progress_snapshot;
--   ALTER TABLE product_manufacturing_times DROP COLUMN product_variant_id;
--   ALTER TABLE sample_order_line_items     DROP COLUMN product_variant_id;
--   ALTER TABLE production_order_line_items DROP COLUMN product_variant_id;
-- =============================================================================

ALTER TABLE production_order_line_items
    ADD COLUMN product_variant_id bigint,
    ADD CONSTRAINT fk_poli_variant_of_product
        FOREIGN KEY (product_variant_id, product_id)
        REFERENCES product_variants (id, product_id);

COMMENT ON COLUMN production_order_line_items.product_variant_id IS
    'Which variant this line orders. NULL = a product without variants (every pre-V78 line). The composite FK proves the variant belongs to this line''s product. The service makes it REQUIRED once the product has live variants.';

ALTER TABLE sample_order_line_items
    ADD COLUMN product_variant_id bigint,
    ADD CONSTRAINT fk_soli_variant_of_product
        FOREIGN KEY (product_variant_id, product_id)
        REFERENCES product_variants (id, product_id);

COMMENT ON COLUMN sample_order_line_items.product_variant_id IS
    'Same contract as on production_order_line_items.';

ALTER TABLE product_manufacturing_times
    ADD COLUMN product_variant_id bigint,
    ADD CONSTRAINT fk_pmt_variant_of_product
        FOREIGN KEY (product_variant_id, product_id)
        REFERENCES product_variants (id, product_id);

COMMENT ON COLUMN product_manufacturing_times.product_variant_id IS
    'The variant this time was configured for. NULL on a product WITH variants is legal and means "all variants": the configurator offered the union and the excluded flags picked what counts (SPEC odluka 9).';

ALTER TABLE production_orders
    ADD COLUMN closed_progress_snapshot jsonb;

COMMENT ON COLUMN production_orders.closed_progress_snapshot IS
    'The order''s progress as it stood when the order closed (DELIVERED/CANCELLED), written in the same transaction as the status: schemaVersion, calculatedAt, work percent, finished-goods range, per line. NULL while the order is open; cleared if it reopens. Reads for a closed order return this instead of recomputing, so later catalogue edits cannot rewrite history.';

-- Postgres does not index FK columns by itself; the progress and picker joins do.
CREATE INDEX ix_poli_variant ON production_order_line_items (product_variant_id)
    WHERE product_variant_id IS NOT NULL;
CREATE INDEX ix_soli_variant ON sample_order_line_items (product_variant_id)
    WHERE product_variant_id IS NOT NULL;
CREATE INDEX ix_pmt_variant ON product_manufacturing_times (product_variant_id)
    WHERE product_variant_id IS NOT NULL;
