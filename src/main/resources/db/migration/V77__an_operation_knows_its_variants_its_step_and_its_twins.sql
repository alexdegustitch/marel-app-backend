-- =============================================================================
-- An operation knows its variants, its step, and its twins
-- =============================================================================
-- WHAT CHANGES
--   · NEW table operation_groups — interchangeable machines doing one job
--   · operations gains three nullable/defaulted columns:
--         operation_group_id   bigint   (composite FK — member shares the product)
--         is_group_default     boolean  NOT NULL DEFAULT false
--         sort_order           integer  (step number; equal = parallel; NULL = unplaced)
--   · NEW table operation_variant_options — the BINDING: which attribute value
--     an operation is tied to. No rows = the operation applies to every
--     variant of its product, which is exactly what every existing operation
--     means today, so NO BACKFILL of any kind.
--
-- WHY
--   · BINDING: the operation "Montaža navrtke Al" exists only for variants
--     with an aluminium nut. Tying it to the VALUE (product + Al), not to a
--     variant, is the point: one shared operation for every variant that has
--     Al — today's two and any future one — with one norm and one history.
--     The service allows exactly ONE row per operation (SPEC odluka 4); the
--     table takes more (one per attribute, AND-semantics) so a future
--     relaxation is a validation change, not a migration.
--   · GROUPS: a new press arrives and does the same job faster. The old
--     operation cannot be archived — the old press runs again whenever the new
--     one stands — so the two become members of one group: same job, each with
--     its own norm and history. One member is the default (what manufacturing
--     time and razrada name); progress counts the UNION of all members. The
--     members must share product (the composite FK says so), binding set,
--     units_per_product and sort_order (the service says so) — otherwise
--     summing their quantities would mean nothing.
--   · SORT ORDER: which step comes first, what runs in parallel. This
--     information existed nowhere until now; NULL marks the whole existing
--     stock as simply "not yet placed".
--
-- WHAT THE DATABASE GUARANTEES
--   · a group member belongs to the group's product (composite FK onto
--     UNIQUE (id, product_id));
--   · a standalone operation (V65, product_id IS NULL) cannot join a group;
--   · at most one default row per group (partial unique index) — "exactly one
--     ACTIVE default per active group" needs is_active and is the service's
--     half of the bargain;
--   · a binding option really belongs to the attribute it names (composite FK,
--     V24 pattern, same as V76).
--
-- ROLLBACK
--   DROP TABLE operation_variant_options;
--   ALTER TABLE operations
--       DROP COLUMN sort_order, DROP COLUMN is_group_default,
--       DROP COLUMN operation_group_id;
--   DROP TABLE operation_groups;
--   DELETE FROM audit_tables WHERE table_name IN
--       ('operation_groups','operation_variant_options');
-- =============================================================================

CREATE TABLE operation_groups (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  bigint       NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    name        varchar(255) NOT NULL,
    note        text,
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    archived_at timestamptz,
    CONSTRAINT chk_og_no_reactivate CHECK (NOT (archived_at IS NOT NULL AND is_active = true)),
    -- Composite-FK target: a member operation provably shares the product.
    CONSTRAINT uq_operation_groups_id_product UNIQUE (id, product_id)
);

COMMENT ON TABLE operation_groups IS
    'Interchangeable operations of one product — the same job on different machines, each member with its own norm and history. One member is the default; progress counts the union of all active members; manufacturing time includes exactly one. The old machine is never archived: it runs again whenever the new one stands.';

CREATE UNIQUE INDEX uq_operation_groups_product_name_ci
    ON operation_groups (product_id, lower(name))
    WHERE archived_at IS NULL;

ALTER TABLE operations
    ADD COLUMN operation_group_id bigint,
    ADD COLUMN is_group_default   boolean NOT NULL DEFAULT false,
    ADD COLUMN sort_order         integer,
    ADD CONSTRAINT fk_operations_group_same_product
        FOREIGN KEY (operation_group_id, product_id)
        REFERENCES operation_groups (id, product_id),
    ADD CONSTRAINT chk_operations_group_needs_product
        CHECK (operation_group_id IS NULL OR product_id IS NOT NULL),
    ADD CONSTRAINT chk_operations_default_needs_group
        CHECK (operation_group_id IS NOT NULL OR is_group_default = false);

COMMENT ON COLUMN operations.operation_group_id IS
    'The group of interchangeable machine-operations this one belongs to, or NULL for the ordinary single operation. The composite FK makes a member share the group''s product.';
COMMENT ON COLUMN operations.is_group_default IS
    'The member manufacturing time preselects and razrada names (one row per group, radio). Changing it never touches historical calculations — those read snapshots.';
COMMENT ON COLUMN operations.sort_order IS
    'Step number within the product: lower runs earlier, EQUAL numbers run in parallel, NULL = not yet placed (the entire pre-V77 stock).';

-- At most one default per group; "exactly one ACTIVE one" is the service's job.
CREATE UNIQUE INDEX ux_operations_one_group_default
    ON operations (operation_group_id)
    WHERE is_group_default;

-- Finding a group's members, and joining COALESCE(group, operation) in progress.
CREATE INDEX ix_operations_group
    ON operations (operation_group_id)
    WHERE operation_group_id IS NOT NULL;

CREATE TABLE operation_variant_options (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id                bigint NOT NULL REFERENCES operations (id) ON DELETE CASCADE,
    variant_attribute_id        bigint NOT NULL,
    variant_attribute_option_id bigint NOT NULL,
    -- One value per attribute per operation. The service further narrows this
    -- to one row per operation altogether (SPEC odluka 4).
    CONSTRAINT uq_ovo_operation_attr UNIQUE (operation_id, variant_attribute_id),
    CONSTRAINT fk_ovo_option_matches_attr
        FOREIGN KEY (variant_attribute_option_id, variant_attribute_id)
        REFERENCES variant_attribute_options (id, variant_attribute_id)
);

COMMENT ON TABLE operation_variant_options IS
    'The binding: this operation exists only for variants carrying these attribute values (today: exactly one row — product + one value). NO rows = the operation applies to every variant of its product, which is what every pre-V77 operation means, so nothing was backfilled.';

-- "Which operations does value X1 pull in" — the karton filter's direction.
CREATE INDEX ix_ovo_option
    ON operation_variant_options (variant_attribute_option_id, operation_id);

-- ── updated_at ───────────────────────────────────────────────────────────────

CREATE TRIGGER trg_03_og_updated_at
    BEFORE UPDATE ON operation_groups
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ── Auditing (V12 pattern; operations itself has been audited since V12) ─────

INSERT INTO public.audit_tables (table_name)
SELECT t.name
FROM (VALUES ('operation_groups'), ('operation_variant_options')) AS t(name)
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables a WHERE a.table_name = t.name
);

DROP TRIGGER IF EXISTS trg_audit_logs_operation_groups ON public.operation_groups;
CREATE TRIGGER trg_audit_logs_operation_groups
    AFTER INSERT OR DELETE OR UPDATE ON public.operation_groups
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_operation_variant_options ON public.operation_variant_options;
CREATE TRIGGER trg_audit_logs_operation_variant_options
    AFTER INSERT OR DELETE OR UPDATE ON public.operation_variant_options
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();
