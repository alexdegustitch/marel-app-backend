-- =============================================================================
-- Four tables nobody ever read
-- =============================================================================
-- WHAT CHANGES
--   Four tables from V1__baseline.sql are dropped, together with their rows in
--   the audit_tables registry:
--
--     livac_categories               2 reference rows, L1/L2
--     plastic_categories             2 reference rows, P1/P2
--     manufacturing_product_times    empty
--     manufacturing_time_operations  empty
--
--   Nothing in the application has ever read or written any of them: no entity
--   maps them, no native query names them, no DB view or function mentions
--   them, the frontend does not know they exist, and no table outside the
--   dropped set holds a foreign key INTO them. The only code that names them at
--   all is the baseline that created them, the archived pre-Flyway SQL under
--   src/main/resources/sql/archive/, and scripts/reset-transactional-data.sql
--   (updated in the same commit).
--
-- WHY EACH ONE IS DEAD
--   · manufacturing_product_times / manufacturing_time_operations are the
--     FIRST attempt at vreme izrade. The restructure recorded in
--     sql/archive/2026-06-02-product-manufacturing-time-restructure.sql
--     replaced them with product_manufacturing_times and
--     product_manufacturing_time_operations — the pair ProductManufacturingTime
--     and ProductManufacturingTimeOperation map, the pair V6 (norm_date_note),
--     V7 (note) and the manufacturing-time requests of V3/V4 all built on, and
--     the pair that actually carries the snapshot/override columns. The old two
--     survived the restructure only because nothing dropped them; both have
--     been empty ever since.
--   · livac_categories (an hourly rate per caster class) and
--     plastic_categories (a norm multiplier per plastic class) are šifarnici
--     the baseline seeded with two rows each and the application never grew a
--     screen, endpoint or calculation for. Neither a norm, a work code
--     category, a compensation scheme nor a payroll calculation has ever looked
--     at them: hourly rates live in employee_payroll_value_history, and norms
--     live in operation_norm_versions / operation_norm_activations.
--
-- WHAT THIS DOES NOT TOUCH
--   · operations_history STAYS. It is written by trg_05_operations_history on
--     every change to operations, and although no code reads it today, it is
--     history — dropping it is a separate decision, not a cleanup.
--   · product_manufacturing_times and product_manufacturing_time_operations —
--     the live pair — are untouched, as are their FKs, triggers and indexes.
--   · audit_logs keeps every row it has. The guard below proves there are none
--     for these four tables (there never were: nothing but the baseline seed
--     ever wrote to them), so no audit history is lost with the registry rows.
--     If a row DID exist, this migration fails instead of deleting it.
--
-- WHAT GOES WITH THE TABLES
--   DROP TABLE takes their own indexes, CHECK and EXCLUDE constraints,
--   identity sequences and triggers with them:
--     livac_categories              trg_02_livac_archived_at,
--                                   trg_03_livac_categories_updated_at,
--                                   trg_audit_logs_livac_categories
--     plastic_categories            trg_02_plastic_categories_archived_at,
--                                   trg_03_plastic_categories_updated_at,
--                                   trg_audit_logs_plastic_categories
--     manufacturing_product_times   trg_01_fill_product_time_name,
--                                   trg_02_..._archived_at,
--                                   trg_03_..._updated_at,
--                                   trg_audit_logs_manufacturing_product_times
--     manufacturing_time_operations trg_02_..._archived_at,
--                                   trg_03_..._updated_at,
--                                   trg_audit_logs_manufacturing_time_operations
--   The shared functions they call (set_updated_at,
--   set_archived_at_on_deactivate, audit_trigger_fn) are used by dozens of
--   other tables and are NOT dropped. fill_product_time_name() loses its only
--   caller but is left in place: dropping a function is not worth a second
--   decision here.
--
--   The two manufacturing tables are dropped in one statement because
--   manufacturing_time_operations holds the only FK into
--   manufacturing_product_times.
--
--   Guarded; replaying is safe.
--
-- ROLLBACK (structure AND the seeded rows; take a pg_dump first anyway)
--   CREATE TABLE public.livac_categories (
--       id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
--       category_no text NOT NULL,
--       category_name text NOT NULL,
--       rsd_per_hour numeric(10,2) NOT NULL,
--       description text,
--       is_active boolean DEFAULT true NOT NULL,
--       valid_from date DEFAULT CURRENT_DATE,
--       valid_until date,
--       created_at timestamp with time zone DEFAULT now() NOT NULL,
--       updated_at timestamp with time zone,
--       archived_at timestamp with time zone,
--       CONSTRAINT livac_categories_pkey PRIMARY KEY (id),
--       CONSTRAINT chk_livac_categories_hourly_rate CHECK (rsd_per_hour >= 0),
--       CONSTRAINT chk_livac_categories_name_not_empty CHECK (length(btrim(category_name)) > 0),
--       CONSTRAINT chk_livac_categories_no_not_empty CHECK (length(btrim(category_no)) > 0),
--       CONSTRAINT chk_livac_categories_no_reactivate CHECK (NOT (archived_at IS NOT NULL AND is_active = true)),
--       CONSTRAINT chk_livac_categories_validity CHECK (valid_until IS NULL OR valid_until >= valid_from),
--       CONSTRAINT ex_livac_categories_no_overlap EXCLUDE USING gist (
--           lower(category_no) WITH =,
--           daterange(valid_from, COALESCE(valid_until, 'infinity'::date)) WITH &&)
--   );
--   CREATE UNIQUE INDEX uq_livac_categories_category_no_name_ci
--       ON public.livac_categories (lower(category_no), lower(category_name));
--   INSERT INTO public.livac_categories
--       (category_no, category_name, rsd_per_hour, valid_from, created_at)
--   VALUES ('L1', 'Livac I klase',  450.00, '2026-01-08', '2026-01-08 15:26:06.360679+01'),
--          ('L2', 'Livac II klase', 380.00, '2026-01-08', '2026-01-08 15:26:06.360679+01');
--
--   CREATE TABLE public.plastic_categories (
--       id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
--       category_no text NOT NULL,
--       category_name text NOT NULL,
--       norm_multiplier numeric(5,2) DEFAULT 1.0 NOT NULL,
--       is_active boolean DEFAULT true NOT NULL,
--       valid_from date DEFAULT CURRENT_DATE,
--       valid_until date,
--       created_at timestamp with time zone DEFAULT now() NOT NULL,
--       updated_at timestamp with time zone,
--       archived_at timestamp with time zone,
--       CONSTRAINT plastic_categories_pkey PRIMARY KEY (id),
--       CONSTRAINT chk_plastic_categories_category_name_not_empty CHECK (length(btrim(category_name)) > 0),
--       CONSTRAINT chk_plastic_categories_category_no_not_empty CHECK (length(btrim(category_no)) > 0),
--       CONSTRAINT chk_plastic_categories_no_reactivate CHECK (NOT (archived_at IS NOT NULL AND is_active = true)),
--       CONSTRAINT chk_plastic_categories_norm_multiplier_range CHECK (norm_multiplier >= 0),
--       CONSTRAINT chk_plastic_categories_validity CHECK (valid_until IS NULL OR valid_until >= valid_from),
--       CONSTRAINT ex_plastic_categories_no_overlap EXCLUDE USING gist (
--           lower(category_no) WITH =,
--           daterange(valid_from, COALESCE(valid_until, 'infinity'::date)) WITH &&)
--   );
--   CREATE UNIQUE INDEX uq_plastic_categories_no_name_ci
--       ON public.plastic_categories (lower(category_no), lower(category_name));
--   INSERT INTO public.plastic_categories
--       (category_no, category_name, norm_multiplier, valid_from, created_at)
--   VALUES ('P1', 'Plastika standard', 1.00, '2026-01-08', '2026-01-08 15:26:06.360679+01'),
--          ('P2', 'Plastika zahtevna', 1.30, '2026-01-08', '2026-01-08 15:26:06.360679+01');
--
--   CREATE TABLE public.manufacturing_product_times (
--       id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
--       product_id bigint NOT NULL,
--       name text NOT NULL,
--       creation_date date DEFAULT CURRENT_DATE NOT NULL,
--       total_production_time_seconds integer NOT NULL,
--       units_per_hour numeric(10,2) NOT NULL,
--       is_active boolean DEFAULT true NOT NULL,
--       created_at timestamp with time zone DEFAULT now() NOT NULL,
--       updated_at timestamp with time zone,
--       archived_at timestamp with time zone,
--       CONSTRAINT manufacturing_product_times_pkey PRIMARY KEY (id),
--       CONSTRAINT fk_manufacturing_product_times_product_id
--           FOREIGN KEY (product_id) REFERENCES public.products(id),
--       CONSTRAINT chk_manufacturing_product_times_name CHECK (length(btrim(name)) > 0),
--       CONSTRAINT chk_manufacturing_product_times_total_production_time_seconds CHECK (total_production_time_seconds > 0),
--       CONSTRAINT chk_manufacturing_product_times_units_per_hour CHECK (units_per_hour > 0)
--   );
--   CREATE INDEX idx_manufacturing_product_times_product_id
--       ON public.manufacturing_product_times (product_id);
--
--   CREATE TABLE public.manufacturing_time_operations (
--       id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
--       product_time_id bigint NOT NULL,
--       operation_id bigint NOT NULL,
--       units_per_assembly integer NOT NULL,
--       units_per_hour numeric(10,2) NOT NULL,
--       operation_time_seconds integer NOT NULL,
--       norm_date date NOT NULL,
--       note text,
--       is_active boolean DEFAULT true NOT NULL,
--       created_at timestamp with time zone DEFAULT now() NOT NULL,
--       updated_at timestamp with time zone,
--       archived_at timestamp with time zone,
--       CONSTRAINT manufacturing_time_operations_pkey PRIMARY KEY (id),
--       CONSTRAINT fk_manufacturing_time_operations_product_time_id
--           FOREIGN KEY (product_time_id) REFERENCES public.manufacturing_product_times(id),
--       CONSTRAINT fk_manufacturing_time_operations_operation_id
--           FOREIGN KEY (operation_id) REFERENCES public.operations(id),
--       CONSTRAINT chk_manufacturing_time_operations_operation_time_seconds CHECK (operation_time_seconds > 0),
--       CONSTRAINT chk_manufacturing_time_operations_units_per_assembly CHECK (units_per_assembly > 0),
--       CONSTRAINT chk_manufacturing_time_operations_units_per_hour CHECK (units_per_hour > 0)
--   );
--   CREATE INDEX idx_manufacturing_time_operations_operation_id
--       ON public.manufacturing_time_operations (operation_id);
--   CREATE INDEX idx_manufacturing_time_operations_product_time_id
--       ON public.manufacturing_time_operations (product_time_id);
--
--   -- The triggers listed above, re-created exactly as V1__baseline.sql has
--   -- them (set_updated_at, set_archived_at_on_deactivate, audit_trigger_fn,
--   -- fill_product_time_name), and then the registry rows at their old ids:
--   INSERT INTO public.audit_tables OVERRIDING SYSTEM VALUE
--   VALUES (8, 'livac_categories'), (9, 'manufacturing_product_times'),
--          (10, 'manufacturing_time_operations'), (15, 'plastic_categories');
-- =============================================================================

-- ----------------------------------------------------------------------------
-- Guard 1 — auditability. Dropping a table whose changes the audit trail
-- remembers would orphan that history behind a registry row we are about to
-- delete. There are none today; if that ever changes, stop instead.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_count bigint;
    v_tables text;
BEGIN
    SELECT count(*), string_agg(DISTINCT t.table_name, ', ' ORDER BY t.table_name)
      INTO v_count, v_tables
      FROM public.audit_logs l
      JOIN public.audit_tables t ON t.id = l.table_id
     WHERE t.table_name IN ('livac_categories',
                            'plastic_categories',
                            'manufacturing_product_times',
                            'manufacturing_time_operations');

    IF v_count > 0 THEN
        RAISE EXCEPTION
            'V70 refuses to drop audited history: % audit_logs row(s) reference %. '
            'Decide what happens to that trail before dropping the tables.',
            v_count, v_tables;
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- Guard 2 — the two manufacturing tables are empty on every database this
-- migration is meant for (the restructure moved the real data to
-- product_manufacturing_times years of commits ago). If a database somewhere
-- still holds rows, they are data nobody has decided to throw away: stop.
-- livac_categories / plastic_categories are deliberately NOT guarded — their
-- two seeded rows each are exactly what this migration retires, and the
-- rollback above restores them verbatim.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_times bigint := 0;
    v_ops   bigint := 0;
BEGIN
    IF to_regclass('public.manufacturing_product_times') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.manufacturing_product_times' INTO v_times;
    END IF;
    IF to_regclass('public.manufacturing_time_operations') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.manufacturing_time_operations' INTO v_ops;
    END IF;

    IF v_times > 0 OR v_ops > 0 THEN
        RAISE EXCEPTION
            'V70 expected the legacy vreme izrade tables to be empty, found % row(s) in '
            'manufacturing_product_times and % in manufacturing_time_operations. '
            'Move or discard them explicitly first.', v_times, v_ops;
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- The drop itself. One statement for the manufacturing pair: the child holds
-- the only FK into the parent.
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS public.manufacturing_time_operations,
                     public.manufacturing_product_times;

DROP TABLE IF EXISTS public.livac_categories;
DROP TABLE IF EXISTS public.plastic_categories;

-- ----------------------------------------------------------------------------
-- The registry rows. audit_trigger_fn resolves a table by NAME against
-- audit_tables, so a row for a table that no longer exists is a name that can
-- never resolve again — and guard 1 proved nothing points at it.
-- ----------------------------------------------------------------------------
DELETE FROM public.audit_tables
 WHERE table_name IN ('livac_categories',
                      'plastic_categories',
                      'manufacturing_product_times',
                      'manufacturing_time_operations');
