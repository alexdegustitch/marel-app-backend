-- =============================================================================
-- A product type carries a template of the operations its products usually need
-- =============================================================================
-- WHAT CHANGES
--   One new table, product_type_operations. Nothing that already exists is
--   altered: operations, operation_norm_versions and every query over them stay
--   exactly as they are.
--
--     · product_type_operations — a BLUEPRINT of operations for a type. It holds
--       only the definition of an operation (name, description, a suggested norm
--       range, units per product, the default work-code category, whether a norm
--       is required, and display order). It is NOT a live operation: no work log,
--       no norm history and no payroll ever reads it.
--
-- WHAT THIS TABLE IS FOR
--   When a new product of a type is created, the user MAY pull these template
--   operations onto the product. Each pulled template becomes an ordinary row in
--   operations, from that moment fully independent of the template. The user can
--   deselect any of them, add others, or take none at all.
--
-- THREE INVARIANTS THIS DESIGN COMMITS TO (decided with the owner)
--   1. RUNTIME READS PRODUCT OPERATIONS ONLY. Norms, payroll, work orders,
--      order progress, analytics and work logs touch public.operations and never
--      this table. A product type carries a blueprint; it carries no behaviour.
--   2. INHERITANCE IS OPTIONAL. A product may be created with none of its type's
--      template operations. Nothing pulls them automatically.
--   3. THE COPY IS A SNAPSHOT. Once a template is copied onto a product, later
--      edits to the template do NOT propagate to products already created. This
--      is what keeps historical norms auditable.
--
-- WHY A SEPARATE TABLE INSTEAD OF operations WITH A NULLABLE product_id
--   A "template" row in operations would break uq_operations_product_op_name_ci
--   (two types may share an operation name) and would force every query that joins
--   operations to work logs, orders, norms and payroll to exclude template rows —
--   dozens of places, each a chance for a template to leak into a calculation. A
--   dedicated, leaner table cannot leak: the runtime code never names it.
--
-- WHY NO norm_date AND NO VERSION HISTORY HERE
--   The blueprint carries only a SUGGESTED norm range. The date a norm applies
--   from, who entered it and who verified it are facts about a real operation on a
--   real product, created when the template is copied — exactly as the existing
--   product-to-product copy already records them via
--   OperationNormInForceService.recordCurrentFromOperation. Putting a date or a
--   version on the template would invent an audit fact that no one actually stated.
--
-- WHY NO BACK-REFERENCE FROM operations TO THE TEMPLATE
--   Deliberately omitted. Product operations are fully independent after the copy;
--   a provenance FK would suggest a live link that does not exist and would go
--   stale the moment an operation is edited or removed.
--
-- MIGRATION IMPACT
--   · Additive only. No existing column, constraint, trigger or function is
--     altered. Every existing product and operation keeps working untouched.
--   · No data is created or moved. Existing products gain nothing automatically;
--     templates are authored later, per type, by hand.
--   · No query breaks. Nothing reads the new table yet, and nothing that reads
--     operations is changed.
--   · The table is registered for auditing, so an added or edited template row
--     records who changed it.
--   · set_updated_at() and the two archive functions are REUSED, not redefined —
--     they already exist and dozens of tables run them.
--   · Ids are left to the identity sequence, never hardcoded, so replaying cannot
--     collide with numbers taken since.
--   · Every step is guarded (IF NOT EXISTS / guarded DO block); replaying is safe.
--   · Rollback:
--       DROP TABLE public.product_type_operations;
--       DELETE FROM public.audit_tables WHERE table_name = 'product_type_operations';
-- =============================================================================


-- ── product_type_operations ──────────────────────────────────────────────────
-- The blueprint of operations for a type. A lean definition only — never a live
-- operation.

CREATE TABLE IF NOT EXISTS public.product_type_operations (
    id              bigint GENERATED ALWAYS AS IDENTITY,

    product_type_id bigint NOT NULL,

    -- The operation's name, as it will be copied onto a product's operation.
    op_name     character varying(255) NOT NULL,
    description text,

    -- A SUGGESTED norm range and units-per-product. Optional: the real norm (with
    -- its date, author and verification) is entered on the product when copied.
    default_min_norm          integer,
    default_max_norm          integer,
    default_units_per_product integer,

    -- The work-code category a copied operation defaults to. ON DELETE SET NULL
    -- mirrors operations.work_code_category_id: retiring a category must not
    -- delete a blueprint.
    work_code_category_id bigint,

    -- Whether the copied operation requires a norm. Mirrors operations.norm_required.
    norm_required boolean NOT NULL DEFAULT true,

    -- Display order within the type's template.
    sort_order  integer NOT NULL DEFAULT 0,

    is_active   boolean NOT NULL DEFAULT true,
    archived_at timestamp with time zone,

    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_type_operations PRIMARY KEY (id),
    CONSTRAINT chk_product_type_operations_op_name_not_empty
        CHECK (length(btrim(op_name)) > 0),
    CONSTRAINT fk_product_type_operations_type
        FOREIGN KEY (product_type_id) REFERENCES public.product_types (id),
    CONSTRAINT fk_product_type_operations_work_code_category
        FOREIGN KEY (work_code_category_id)
        REFERENCES public.work_code_categories (id) ON DELETE SET NULL
);

-- One operation name per type, compared case-insensitively — the same rule the
-- product level enforces with uq_operations_product_op_name_ci.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_type_operations_type_op_name_ci
    ON public.product_type_operations (product_type_id, lower((op_name)::text));

-- Listing a type's template in order.
CREATE INDEX IF NOT EXISTS idx_product_type_operations_type_order
    ON public.product_type_operations (product_type_id, sort_order)
    WHERE is_active;


-- ── Triggers ─────────────────────────────────────────────────────────────────
-- Numeric prefixes are firing order (PostgreSQL runs same-event triggers in name
-- order). All functions already exist and are shared across the schema.

DROP TRIGGER IF EXISTS trg_01_product_type_operations_clear_archive_on_reactivate ON public.product_type_operations;
CREATE TRIGGER trg_01_product_type_operations_clear_archive_on_reactivate
    BEFORE UPDATE ON public.product_type_operations
    FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();

DROP TRIGGER IF EXISTS trg_02_product_type_operations_archived_at ON public.product_type_operations;
CREATE TRIGGER trg_02_product_type_operations_archived_at
    BEFORE UPDATE ON public.product_type_operations
    FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_product_type_operations_updated_at ON public.product_type_operations;
CREATE TRIGGER trg_03_product_type_operations_updated_at
    BEFORE UPDATE ON public.product_type_operations
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


-- ── Auditing ─────────────────────────────────────────────────────────────────
-- audit_trigger_fn resolves the table by NAME against audit_tables, so the
-- registration has to exist before the trigger can record anything.

INSERT INTO public.audit_tables (table_name)
SELECT 'product_type_operations'
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables a WHERE a.table_name = 'product_type_operations'
);

DROP TRIGGER IF EXISTS trg_audit_logs_product_type_operations ON public.product_type_operations;
CREATE TRIGGER trg_audit_logs_product_type_operations
    AFTER INSERT OR DELETE OR UPDATE ON public.product_type_operations
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


-- ── What the table means ─────────────────────────────────────────────────────

COMMENT ON TABLE public.product_type_operations IS
    'Blueprint of operations for a product type. A lean definition only (name, suggested norm range, units, default work-code category, order) — never a live operation. When a product is created the user MAY copy these onto it as ordinary rows in operations, which are then fully independent (a snapshot). Runtime — norms, payroll, work orders, analytics — reads operations only and never this table.';

COMMENT ON COLUMN public.product_type_operations.default_min_norm IS
    'Suggested lower norm for a copied operation. The real, dated, verifiable norm is entered on the product at copy time, not here.';
COMMENT ON COLUMN public.product_type_operations.default_max_norm IS
    'Suggested upper norm for a copied operation. See default_min_norm.';
COMMENT ON COLUMN public.product_type_operations.work_code_category_id IS
    'Default work-code category a copied operation starts with. ON DELETE SET NULL: retiring a category must not delete the blueprint.';
