-- =============================================================================
-- The product catalog gets a family, a type, and the attributes that vary
-- =============================================================================
-- WHAT CHANGES
--   Five new tables and five new nullable columns on products. Nothing that
--   already exists is altered in place beyond ADD COLUMN.
--
--     · product_families        — the top grouping (e.g. "Kablovske papučice i
--                                 čaure"). Name, description, an ordering, images.
--     · product_types           — the middle grouping (e.g. code "CuCPST"), each
--                                 belonging to exactly one family. Name, code,
--                                 description, note, images.
--     · product_type_attributes — the SPEC SCHEMA of a type: which technical
--                                 attributes its products carry (cross_section,
--                                 d1, L, weight…), with unit and data type. The
--                                 type defines the columns; the product fills them.
--     · product_attribute_values — one product's value for one such attribute.
--     · images                  — one shared image store for families, types and
--                                 products. Many per owner, exactly one primary.
--
--     · products.product_type_id  — nullable FK to product_types.
--     · products.catalog_number   — the catalogue number (e.g. 390010).
--     · products.subtype          — the discriminator inside the code (CBM 95 → 95).
--     · products.supervisor_name  — free-text person responsible, optional.
--     · products.display_name     — optional override of the shown name; when
--                                   NULL the UI shows product_name + ' ' + subtype.
--
-- WHY NULLABLE ON products.product_type_id
--   Plenty of products belong to no family — internal/administration items, and
--   every product already in the database. NULL is their correct, permanent
--   answer, not a gap: NOT NULL would be a lie with a placeholder in it, and this
--   migration invents no data. Existing rows keep working untouched.
--
-- WHY THE ATTRIBUTES LIVE ON THE TYPE, NOT THE PRODUCT
--   In the catalogue every product of one type shares the same spec columns
--   (all CuCPST have a, D, D1, D2, crimpings, weight; all CBM have d1, d2, L, L1).
--   So the schema is a property of the TYPE and the numbers are a property of the
--   PRODUCT. Modelling it that way gives consistent columns, real validation and
--   plain tabular reports/exports, instead of a free-for-all bag of keys per row.
--
-- WHY ONE SHARED images TABLE WITH REAL FKs
--   The same "many images, one primary" need exists on all three levels. One
--   table with three nullable FKs and a CHECK that exactly one is set keeps every
--   reference a real foreign key (no polymorphic owner_type/owner_id that the
--   database cannot enforce), while not duplicating the mechanism three times.
--
-- WHY set_updated_at() AND THE ARCHIVE FUNCTIONS ARE REUSED, NOT REDEFINED
--   They already exist and dozens of tables run them. set_updated_at bumps
--   updated_at only when a column OTHER than updated_at changed; redefining it
--   would move a timestamp that feeds the audit trail. This migration adds
--   triggers and touches no function.
--
-- MIGRATION IMPACT
--   · Additive only. No existing column, constraint, trigger or function is
--     altered; every existing product row starts with the five new columns NULL,
--     meaning "uncategorised / no override", a permanent and correct answer.
--   · No query breaks. Nothing selected products.* positionally, and the new
--     columns are simply absent from every existing statement.
--   · products is already audited, so the new columns begin recording who
--     changed them from the first write. No back-fill of history happens.
--   · All five new tables are registered for auditing, so a changed family name
--     or a corrected spec value carries who changed it.
--   · Ids are left to the identity sequences, not hardcoded, so replaying cannot
--     collide with numbers taken since.
--   · Every step is guarded (IF NOT EXISTS / guarded DO blocks); replaying is safe.
--   · Rollback:
--       DROP TABLE public.images;
--       DROP TABLE public.product_attribute_values;
--       DROP TABLE public.product_type_attributes;
--       ALTER TABLE public.products DROP COLUMN display_name;
--       ALTER TABLE public.products DROP COLUMN supervisor_name;
--       ALTER TABLE public.products DROP COLUMN subtype;
--       ALTER TABLE public.products DROP COLUMN catalog_number;
--       ALTER TABLE public.products DROP COLUMN product_type_id;
--       DROP TABLE public.product_types;
--       DROP TABLE public.product_families;
--       DELETE FROM public.audit_tables WHERE table_name IN
--         ('product_families','product_types','product_type_attributes',
--          'product_attribute_values','images');
-- =============================================================================


-- ── product_families ─────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.product_families (
    id          bigint GENERATED ALWAYS AS IDENTITY,

    name        character varying(255) NOT NULL,
    description text,

    -- Deliberate display order; the catalogue is not alphabetical.
    sort_order  integer NOT NULL DEFAULT 0,

    is_active   boolean NOT NULL DEFAULT true,
    archived_at timestamp with time zone,

    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_families PRIMARY KEY (id),
    CONSTRAINT chk_product_families_name_not_empty
        CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_families_name_ci
    ON public.product_families (lower((name)::text));

CREATE INDEX IF NOT EXISTS idx_product_families_active_order
    ON public.product_families (sort_order, name)
    WHERE is_active;


-- ── product_types ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.product_types (
    id          bigint GENERATED ALWAYS AS IDENTITY,

    family_id   bigint NOT NULL,

    name        character varying(255) NOT NULL,
    -- The code that names the type in the catalogue: "CuCPST", "CBM".
    code        character varying(50)  NOT NULL,
    description text,
    note        text,

    sort_order  integer NOT NULL DEFAULT 0,

    is_active   boolean NOT NULL DEFAULT true,
    archived_at timestamp with time zone,

    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_types PRIMARY KEY (id),
    CONSTRAINT chk_product_types_name_not_empty
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_product_types_code_not_empty
        CHECK (length(btrim(code)) > 0),
    CONSTRAINT fk_product_types_family
        FOREIGN KEY (family_id) REFERENCES public.product_families (id)
);

-- Type codes are unique per family, case-insensitively (people type them).
-- The same code may recur under a different family.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_types_code_ci
    ON public.product_types (family_id, lower((code)::text));

CREATE INDEX IF NOT EXISTS idx_product_types_family
    ON public.product_types (family_id);

CREATE INDEX IF NOT EXISTS idx_product_types_active_order
    ON public.product_types (family_id, sort_order, name)
    WHERE is_active;


-- ── product_type_attributes ──────────────────────────────────────────────────
-- The spec schema of a type: the columns its products carry.

CREATE TABLE IF NOT EXISTS public.product_type_attributes (
    id              bigint GENERATED ALWAYS AS IDENTITY,

    product_type_id bigint NOT NULL,

    -- The attribute's key/label, e.g. "cross_section", "d1", "L", "weight".
    name        character varying(100) NOT NULL,
    -- Unit of measure, when it has one: "mm", "mm²", "kg/100", "kom".
    unit        character varying(30),
    -- How the value is interpreted and validated.
    data_type   character varying(20) NOT NULL DEFAULT 'TEXT',
    -- Column order as it appears in the catalogue.
    sort_order  integer NOT NULL DEFAULT 0,
    is_required boolean NOT NULL DEFAULT false,
    is_active   boolean NOT NULL DEFAULT true,

    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_type_attributes PRIMARY KEY (id),
    CONSTRAINT chk_product_type_attributes_name_not_empty
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_product_type_attributes_data_type
        CHECK (data_type IN ('NUMBER', 'TEXT')),
    CONSTRAINT fk_product_type_attributes_type
        FOREIGN KEY (product_type_id) REFERENCES public.product_types (id)
);

-- One attribute name per type, compared case-insensitively.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_type_attributes_type_name_ci
    ON public.product_type_attributes (product_type_id, lower((name)::text));

CREATE INDEX IF NOT EXISTS idx_product_type_attributes_type_order
    ON public.product_type_attributes (product_type_id, sort_order)
    WHERE is_active;


-- ── products: the new columns ────────────────────────────────────────────────

ALTER TABLE public.products
    ADD COLUMN IF NOT EXISTS product_type_id bigint,
    ADD COLUMN IF NOT EXISTS catalog_number  character varying(50),
    ADD COLUMN IF NOT EXISTS subtype         character varying(50),
    ADD COLUMN IF NOT EXISTS supervisor_name character varying(255),
    ADD COLUMN IF NOT EXISTS display_name    character varying(255);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_products_product_type'
    ) THEN
        ALTER TABLE public.products
            ADD CONSTRAINT fk_products_product_type
            FOREIGN KEY (product_type_id) REFERENCES public.product_types (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_products_product_type
    ON public.products (product_type_id)
    WHERE product_type_id IS NOT NULL;

-- Catalogue numbers are unique among the products that carry one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_catalog_number_ci
    ON public.products (lower((catalog_number)::text))
    WHERE catalog_number IS NOT NULL;


-- ── product_attribute_values ─────────────────────────────────────────────────
-- One product's value for one attribute of its type.
--
-- INVARIANT NOT EXPRESSED IN SQL: the referenced attribute must belong to the
-- referenced product's type. A composite FK cannot state it (product_type_id is
-- nullable on products), so it is enforced in the service layer and covered by a
-- test. product_id CASCADEs (values are pure child data of the product), while
-- product_type_attribute_id does NOT: an attribute definition still in use by any
-- product cannot be hard-deleted.

CREATE TABLE IF NOT EXISTS public.product_attribute_values (
    id                        bigint GENERATED ALWAYS AS IDENTITY,

    product_id                bigint NOT NULL,
    product_type_attribute_id bigint NOT NULL,

    -- Stored as text; parsed according to the attribute's data_type.
    value      character varying(255),

    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_attribute_values PRIMARY KEY (id),
    CONSTRAINT fk_product_attribute_values_product
        FOREIGN KEY (product_id) REFERENCES public.products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_attribute_values_attribute
        FOREIGN KEY (product_type_attribute_id)
        REFERENCES public.product_type_attributes (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_attribute_values_product_attr
    ON public.product_attribute_values (product_id, product_type_attribute_id);

CREATE INDEX IF NOT EXISTS idx_product_attribute_values_attribute
    ON public.product_attribute_values (product_type_attribute_id);


-- ── images ───────────────────────────────────────────────────────────────────
-- One store for families, types and products. Exactly one owner per row; the
-- file itself lives outside the database and url points at it.

CREATE TABLE IF NOT EXISTS public.images (
    id                bigint GENERATED ALWAYS AS IDENTITY,

    product_family_id bigint,
    product_type_id   bigint,
    product_id        bigint,

    url        character varying(500) NOT NULL,
    alt_text   character varying(255),
    is_primary boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,

    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_images PRIMARY KEY (id),
    CONSTRAINT chk_images_url_not_empty
        CHECK (length(btrim(url)) > 0),
    -- Exactly one owner.
    CONSTRAINT chk_images_one_owner CHECK (
        (product_family_id IS NOT NULL)::int
      + (product_type_id   IS NOT NULL)::int
      + (product_id        IS NOT NULL)::int = 1
    ),
    CONSTRAINT fk_images_family
        FOREIGN KEY (product_family_id)
        REFERENCES public.product_families (id) ON DELETE CASCADE,
    CONSTRAINT fk_images_type
        FOREIGN KEY (product_type_id)
        REFERENCES public.product_types (id) ON DELETE CASCADE,
    CONSTRAINT fk_images_product
        FOREIGN KEY (product_id)
        REFERENCES public.products (id) ON DELETE CASCADE
);

-- At most one primary image per owner.
CREATE UNIQUE INDEX IF NOT EXISTS uq_images_primary_family
    ON public.images (product_family_id)
    WHERE is_primary AND product_family_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_images_primary_type
    ON public.images (product_type_id)
    WHERE is_primary AND product_type_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_images_primary_product
    ON public.images (product_id)
    WHERE is_primary AND product_id IS NOT NULL;

-- Listing an owner's images in order.
CREATE INDEX IF NOT EXISTS idx_images_family
    ON public.images (product_family_id, sort_order)
    WHERE product_family_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_images_type
    ON public.images (product_type_id, sort_order)
    WHERE product_type_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_images_product
    ON public.images (product_id, sort_order)
    WHERE product_id IS NOT NULL;


-- ── Triggers ─────────────────────────────────────────────────────────────────
-- Numeric prefixes are firing order (PostgreSQL runs same-event triggers in name
-- order). All functions already exist and are shared across the schema.

-- product_families: soft-archive + updated_at
DROP TRIGGER IF EXISTS trg_01_product_families_clear_archive_on_reactivate ON public.product_families;
CREATE TRIGGER trg_01_product_families_clear_archive_on_reactivate
    BEFORE UPDATE ON public.product_families
    FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();

DROP TRIGGER IF EXISTS trg_02_product_families_archived_at ON public.product_families;
CREATE TRIGGER trg_02_product_families_archived_at
    BEFORE UPDATE ON public.product_families
    FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_product_families_updated_at ON public.product_families;
CREATE TRIGGER trg_03_product_families_updated_at
    BEFORE UPDATE ON public.product_families
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- product_types: soft-archive + updated_at
DROP TRIGGER IF EXISTS trg_01_product_types_clear_archive_on_reactivate ON public.product_types;
CREATE TRIGGER trg_01_product_types_clear_archive_on_reactivate
    BEFORE UPDATE ON public.product_types
    FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();

DROP TRIGGER IF EXISTS trg_02_product_types_archived_at ON public.product_types;
CREATE TRIGGER trg_02_product_types_archived_at
    BEFORE UPDATE ON public.product_types
    FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_product_types_updated_at ON public.product_types;
CREATE TRIGGER trg_03_product_types_updated_at
    BEFORE UPDATE ON public.product_types
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- product_type_attributes: updated_at only (is_active is a plain flag here)
DROP TRIGGER IF EXISTS trg_03_product_type_attributes_updated_at ON public.product_type_attributes;
CREATE TRIGGER trg_03_product_type_attributes_updated_at
    BEFORE UPDATE ON public.product_type_attributes
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- product_attribute_values: updated_at
DROP TRIGGER IF EXISTS trg_03_product_attribute_values_updated_at ON public.product_attribute_values;
CREATE TRIGGER trg_03_product_attribute_values_updated_at
    BEFORE UPDATE ON public.product_attribute_values
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- images: updated_at
DROP TRIGGER IF EXISTS trg_03_images_updated_at ON public.images;
CREATE TRIGGER trg_03_images_updated_at
    BEFORE UPDATE ON public.images
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


-- ── Auditing ─────────────────────────────────────────────────────────────────
-- audit_trigger_fn resolves the table by NAME against audit_tables, so the
-- registration has to exist before the trigger can record anything. Ids come
-- from the identity sequence, never hardcoded.

INSERT INTO public.audit_tables (table_name)
SELECT v.name
FROM (VALUES
    ('product_families'),
    ('product_types'),
    ('product_type_attributes'),
    ('product_attribute_values'),
    ('images')
) AS v(name)
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables a WHERE a.table_name = v.name
);

DROP TRIGGER IF EXISTS trg_audit_logs_product_families ON public.product_families;
CREATE TRIGGER trg_audit_logs_product_families
    AFTER INSERT OR DELETE OR UPDATE ON public.product_families
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_product_types ON public.product_types;
CREATE TRIGGER trg_audit_logs_product_types
    AFTER INSERT OR DELETE OR UPDATE ON public.product_types
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_product_type_attributes ON public.product_type_attributes;
CREATE TRIGGER trg_audit_logs_product_type_attributes
    AFTER INSERT OR DELETE OR UPDATE ON public.product_type_attributes
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_product_attribute_values ON public.product_attribute_values;
CREATE TRIGGER trg_audit_logs_product_attribute_values
    AFTER INSERT OR DELETE OR UPDATE ON public.product_attribute_values
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_images ON public.images;
CREATE TRIGGER trg_audit_logs_images
    AFTER INSERT OR DELETE OR UPDATE ON public.images
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


-- ── What the columns mean ────────────────────────────────────────────────────

COMMENT ON TABLE public.product_families IS
    'Top level of the catalogue hierarchy (family → type → product). Deactivated, never deleted.';
COMMENT ON TABLE public.product_types IS
    'Middle level: a kind of product within a family, named by a code (CuCPST, CBM). Owns the spec schema its products fill in.';
COMMENT ON TABLE public.product_type_attributes IS
    'The spec columns a type''s products carry (cross_section, d1, L, weight…), with unit and data type. The type defines the schema; each product supplies the values.';
COMMENT ON TABLE public.product_attribute_values IS
    'One product''s value for one attribute of its type. The referenced attribute must belong to the product''s type — enforced in the service layer.';
COMMENT ON TABLE public.images IS
    'Image store shared by families, types and products. Exactly one owner per row; the file lives outside the database and url points at it. At most one primary per owner.';

COMMENT ON COLUMN public.products.product_type_id IS
    'The type this product belongs to, when it belongs to one. NULL means uncategorised/administration — a permanent answer, not a gap.';
COMMENT ON COLUMN public.products.catalog_number IS
    'Catalogue number (e.g. 390010). Unique among the products that carry one.';
COMMENT ON COLUMN public.products.subtype IS
    'The discriminator inside the product code (CBM 95 → 95). A label, not a hierarchy level.';
COMMENT ON COLUMN public.products.supervisor_name IS
    'Free-text person responsible for the product. Optional.';
COMMENT ON COLUMN public.products.display_name IS
    'Optional override of the shown name. When NULL the UI shows product_name + '' '' + subtype.';
