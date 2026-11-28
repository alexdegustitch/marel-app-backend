-- =============================================================================
-- A product can come in variants
-- =============================================================================
-- WHAT CHANGES
--   Four NEW tables, nothing existing is touched:
--
--       variant_attributes          what a TYPE varies by      ("Šraf", "Navrtka")
--       variant_attribute_options   the values it can take     ("Prohrom", "Al", "Cu")
--       product_variants            one concrete combination of one product
--       product_variant_values      which option the variant picked, per attribute
--
-- WHY
--   Products of one type can differ in HOW they are made — which screw, which
--   nut — while staying the same product. Today the catalogue cannot say this:
--   an IVS PA 450 with a copper nut and one with an aluminium nut are either
--   one row (and the difference is lost) or two products (and every operation,
--   norm and history is duplicated).
--
--   These are NOT the descriptive attributes of V34 (product_type_attributes /
--   product_attribute_values). Those describe how a product LOOKS on paper —
--   cross-section, weight — and all 556 imported definitions stay exactly where
--   they are. The owner's words: one set describes the LOOK, this set describes
--   the WAY it is made. Keeping them apart in the schema is the decision, not
--   an accident (docs/specs/varijante-i-grupe-operacija-SPEC.md, odluka 1).
--
-- THE KEYS, AND WHAT THE DATABASE ITSELF GUARANTEES
--   · variant_attribute_options carries UNIQUE (id, variant_attribute_id) — a
--     redundant-looking pair whose only job is to be the TARGET of composite
--     foreign keys (the V24 pattern), so that "this option really belongs to
--     that attribute" is checked by the database, not by trust.
--   · product_variant_values stores variant_attribute_id DENORMALIZED on
--     purpose: UNIQUE (product_variant_id, variant_attribute_id) makes "one
--     option per attribute within a variant" a constraint, and the composite
--     FK to (id, variant_attribute_id) makes the denormalized column honest.
--   · product_variants.signature is the canonical form of the combination —
--     the option ids sorted and joined ("1001|1101"), written by the service
--     in the same transaction as the values. The partial unique index on
--     (product_id, signature) WHERE archived_at IS NULL is the only way the
--     database itself can refuse two identical LIVE combinations.
--   · UNIQUE (id, product_id) on product_variants is again composite-FK bait:
--     V78 points order lines at it so a line's variant provably belongs to the
--     line's product.
--
--   What stays in the SERVICE (same compromise as V34, products.product_type_id
--   is nullable so the database cannot say it): the attribute belongs to the
--   type of THAT product; a variant carries a value for EVERY required
--   attribute; a variant referenced by an order line or a manufacturing time
--   is frozen — editing its values is refused, a changed combination is a NEW
--   variant (SPEC odluka 13).
--
-- ROLLBACK
--   DROP TABLE product_variant_values;
--   DROP TABLE product_variants;
--   DROP TABLE variant_attribute_options;
--   DROP TABLE variant_attributes;
--   DELETE FROM audit_tables WHERE table_name IN
--       ('variant_attributes','variant_attribute_options',
--        'product_variants','product_variant_values');
-- =============================================================================

CREATE TABLE variant_attributes (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_type_id bigint       NOT NULL REFERENCES product_types (id) ON DELETE CASCADE,
    name            varchar(100) NOT NULL,
    is_required     boolean      NOT NULL DEFAULT true,
    sort_order      integer      NOT NULL DEFAULT 0,
    is_active       boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    archived_at     timestamptz,
    CONSTRAINT chk_va_no_reactivate CHECK (NOT (archived_at IS NOT NULL AND is_active = true))
);

COMMENT ON TABLE variant_attributes IS
    'What products of one type vary by in the WAY they are made ("Šraf", "Navrtka"). Deliberately separate from product_type_attributes (V34), which describe the look; none of those 556 imported definitions ever becomes one of these.';
COMMENT ON COLUMN variant_attributes.is_required IS
    'true = every variant of a product of this type must pick a value for it; false = a variant may leave it out.';

CREATE UNIQUE INDEX uq_variant_attributes_type_name_ci
    ON variant_attributes (product_type_id, lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE variant_attribute_options (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_attribute_id bigint       NOT NULL REFERENCES variant_attributes (id) ON DELETE CASCADE,
    value                varchar(100) NOT NULL,
    sort_order           integer      NOT NULL DEFAULT 0,
    is_active            boolean      NOT NULL DEFAULT true,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz,
    archived_at          timestamptz,
    CONSTRAINT chk_vao_no_reactivate CHECK (NOT (archived_at IS NOT NULL AND is_active = true)),
    -- Composite-FK target (V24 pattern): lets referencing tables prove their
    -- option belongs to the attribute they claim it does.
    CONSTRAINT uq_vao_id_attr UNIQUE (id, variant_attribute_id)
);

COMMENT ON TABLE variant_attribute_options IS
    'The values a variant attribute can take ("Prohrom", "Al", "Cu", "Ms"). A deactivated option is not offered for NEW variants or bindings; existing ones that use it stay valid.';

CREATE UNIQUE INDEX uq_vao_attr_value_ci
    ON variant_attribute_options (variant_attribute_id, lower(value))
    WHERE archived_at IS NULL;

CREATE TABLE product_variants (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  bigint      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    label       varchar(255),
    signature   text        NOT NULL,
    is_active   boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    archived_at timestamptz,
    CONSTRAINT chk_pv_no_reactivate CHECK (NOT (archived_at IS NOT NULL AND is_active = true)),
    -- Composite-FK target: an order line's (variant, product) pair must agree.
    CONSTRAINT uq_product_variants_id_product UNIQUE (id, product_id)
);

COMMENT ON TABLE product_variants IS
    'One concrete combination of variant-attribute options for one product ("IVS PA 450 · Prohrom / Al"). Once referenced by an order line or a manufacturing time it is FROZEN: the service refuses value edits, a different combination is a new variant.';
COMMENT ON COLUMN product_variants.label IS
    'Optional hand-given name; NULL means the UI composes one from the option values.';
COMMENT ON COLUMN product_variants.signature IS
    'Canonical form of the combination: option ids sorted ascending, joined with "|" ("1001|1101"). Written by the service in the same transaction as the values; the partial unique below is what makes two identical live combinations impossible.';

CREATE UNIQUE INDEX uq_product_variants_signature
    ON product_variants (product_id, signature)
    WHERE archived_at IS NULL;

CREATE TABLE product_variant_values (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_variant_id          bigint NOT NULL REFERENCES product_variants (id) ON DELETE CASCADE,
    variant_attribute_id        bigint NOT NULL,
    variant_attribute_option_id bigint NOT NULL,
    -- One option per attribute within a variant — in the database, not in code.
    CONSTRAINT uq_pvv_variant_attr UNIQUE (product_variant_id, variant_attribute_id),
    -- The denormalized attribute id is honest: the option really is one of
    -- that attribute's options.
    CONSTRAINT fk_pvv_option_matches_attr
        FOREIGN KEY (variant_attribute_option_id, variant_attribute_id)
        REFERENCES variant_attribute_options (id, variant_attribute_id)
);

COMMENT ON TABLE product_variant_values IS
    'Which option a variant picked, one row per attribute. variant_attribute_id is denormalized ON PURPOSE: it lets the two constraints above hold in the database.';

CREATE INDEX idx_pvv_option ON product_variant_values (variant_attribute_option_id);
CREATE INDEX idx_pv_product ON product_variants (product_id) WHERE archived_at IS NULL;

-- ── updated_at ───────────────────────────────────────────────────────────────

CREATE TRIGGER trg_03_va_updated_at
    BEFORE UPDATE ON variant_attributes
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER trg_03_vao_updated_at
    BEFORE UPDATE ON variant_attribute_options
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER trg_03_pv_updated_at
    BEFORE UPDATE ON product_variants
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ── Auditing (V12 pattern) ───────────────────────────────────────────────────

INSERT INTO public.audit_tables (table_name)
SELECT t.name
FROM (VALUES ('variant_attributes'), ('variant_attribute_options'),
             ('product_variants'), ('product_variant_values')) AS t(name)
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables a WHERE a.table_name = t.name
);

DROP TRIGGER IF EXISTS trg_audit_logs_variant_attributes ON public.variant_attributes;
CREATE TRIGGER trg_audit_logs_variant_attributes
    AFTER INSERT OR DELETE OR UPDATE ON public.variant_attributes
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_variant_attribute_options ON public.variant_attribute_options;
CREATE TRIGGER trg_audit_logs_variant_attribute_options
    AFTER INSERT OR DELETE OR UPDATE ON public.variant_attribute_options
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_product_variants ON public.product_variants;
CREATE TRIGGER trg_audit_logs_product_variants
    AFTER INSERT OR DELETE OR UPDATE ON public.product_variants
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_product_variant_values ON public.product_variant_values;
CREATE TRIGGER trg_audit_logs_product_variant_values
    AFTER INSERT OR DELETE OR UPDATE ON public.product_variant_values
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();
