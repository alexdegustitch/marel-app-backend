-- =============================================================================
-- An absence operation needs no technical product
-- =============================================================================
-- WHAT CHANGES
--   1. operations.product_id may now be NULL, and a new column says when that
--      is on purpose:
--        is_standalone  boolean NOT NULL DEFAULT false
--      TRUE marks an OSNOVNA (standalone) operation — work that is logged on a
--      karton but belongs to no product: bolovanje in its three kinds, godišnji
--      odmor, neplaćeno odsustvo, neradni dan. Three CHECKs state the whole
--      rule:
--        · standalone  ⇔  product_id IS NULL (the flag and the column can
--          never disagree);
--        · a standalone operation carries NO norm — norm_required false, no
--          min/max, no units, no date, not "privremena". There is no piece to
--          count;
--        · a standalone operation MUST carry a work code category. It is the
--          only thing left that says what the logged time is worth, so it is
--          not optional the way it is for product operations.
--      Standalone names get their own case-insensitive uniqueness (a partial
--      index), because uq_operations_product_op_name_ci never compares two
--      NULL products.
--   2. The six full-day absence operations stop pretending. V25 and V40
--      created one "technical product" per full-day category only because
--      product_id was NOT NULL; every operation whose category says
--      is_full_day = true is converted to standalone here, targeted through
--      the flag rather than by name — the live rows predate V25/V40 and were
--      named by hand. Their work_logs rows are untouched: the log keeps its
--      operation, the operation simply no longer claims a product.
--   3. The technical products left empty by that conversion are archived — but
--      only where nothing else references them (no remaining operations, no
--      production or sample order line items). A row that is still referenced
--      stays active for a person to deal with; a migration hides nothing it
--      cannot prove orphaned.
--   4. work_log_facts.product_id and product_name may now be NULL: the facts
--      row for a standalone log has no product to name. Existing facts keep
--      the technical product they recorded until their shift is next
--      recalculated — history is not rewritten.
--
-- WHY A FLAG AND NOT "product_id IS NULL" ALONE
--   The CHECK ties them, so the flag adds no second truth — it adds a NAME.
--   Queries, projections and the frontend say o.is_standalone instead of
--   re-deriving meaning from an absent join, and the column is indexable for
--   the board's KPI filter. (Deliberately NOT called "basic": work_code_
--   categories.is_basic_work_operation already means something else, and V49
--   exists because that collision bit once before.)
--
-- WHAT HAPPENS TO EXISTING DATA
--   Only the full-day absence operations move, and only their product link,
--   norm columns (all already empty or meaningless — norm_required is false on
--   every one) and the new flag change. No work_logs row, no payroll figure,
--   no norm-version history row is touched. The archived technical products
--   keep their rows and their history; archiving is reversible by hand.
--
--   Guarded; replaying is safe (a converted operation has product_id NULL and
--   falls out of the conversion's WHERE).
--
-- Rollback:
--   Not mechanical. The conversion can be undone by re-creating the technical
--   products (V25/V40 show how), re-pointing each standalone operation at its
--   product and dropping the new column, checks and index — but nothing
--   below loses data, so the honest rollback is a new migration.
-- =============================================================================

-- ── 1 · Schema: the column, loosened NOT NULLs ──────────────────────────────

ALTER TABLE public.operations
    ALTER COLUMN product_id DROP NOT NULL;

ALTER TABLE public.operations
    ADD COLUMN IF NOT EXISTS is_standalone boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public.operations.is_standalone IS
    'TRUE for an OSNOVNA operation — logged on kartoni but belonging to no product (bolovanje, godišnji odmor, neplaćeno odsustvo, neradni dan). Always equals product_id IS NULL; carries no norm; must carry a work code category.';

ALTER TABLE public.work_log_facts
    ALTER COLUMN product_id DROP NOT NULL;

ALTER TABLE public.work_log_facts
    ALTER COLUMN product_name DROP NOT NULL;

-- ── 2 · Data: the full-day absence operations become standalone ─────────────

DO $$
BEGIN
    CREATE TEMP TABLE _v65_converted ON COMMIT DROP AS
    SELECT o.id AS operation_id, o.product_id
    FROM public.operations o
    JOIN public.work_code_categories c ON c.id = o.work_code_category_id
    WHERE c.is_full_day
      AND o.product_id IS NOT NULL;

    UPDATE public.operations o
       SET product_id        = NULL,
           is_standalone     = true,
           norm_required     = false,
           min_norm          = NULL,
           max_norm          = NULL,
           units_per_product = NULL,
           norm_date         = NULL,
           is_temporary      = false
     WHERE o.id IN (SELECT operation_id FROM _v65_converted);

    -- ── 3 · The emptied technical products are archived where provably orphan.
    -- The AFTER-update archive cascade finds no operations left to archive;
    -- the audit trigger is NULL-safe without an app.user_id.
    UPDATE public.products p
       SET is_active   = false,
           archived_at = now()
     WHERE p.id IN (SELECT DISTINCT product_id FROM _v65_converted)
       AND p.archived_at IS NULL
       AND NOT EXISTS (SELECT 1 FROM public.operations o
                        WHERE o.product_id = p.id)
       AND NOT EXISTS (SELECT 1 FROM public.production_order_line_items li
                        WHERE li.product_id = p.id)
       AND NOT EXISTS (SELECT 1 FROM public.sample_order_line_items li
                        WHERE li.product_id = p.id);
END $$;

-- ── 4 · Constraints, after the data they describe is in place ───────────────

ALTER TABLE public.operations
    ADD CONSTRAINT chk_operations_standalone_product
    CHECK (is_standalone = (product_id IS NULL));

ALTER TABLE public.operations
    ADD CONSTRAINT chk_operations_standalone_no_norm
    CHECK (NOT is_standalone
           OR (norm_required = false
               AND min_norm IS NULL
               AND max_norm IS NULL
               AND units_per_product IS NULL
               AND norm_date IS NULL
               AND is_temporary = false));

ALTER TABLE public.operations
    ADD CONSTRAINT chk_operations_standalone_category
    CHECK (NOT is_standalone OR work_code_category_id IS NOT NULL);

-- Standalone names are unique among themselves, case-insensitively — the
-- product-scoped unique constraint never sees them (NULLs do not compare).
CREATE UNIQUE INDEX IF NOT EXISTS uq_operations_standalone_op_name_ci
    ON public.operations (lower(op_name))
    WHERE product_id IS NULL;
