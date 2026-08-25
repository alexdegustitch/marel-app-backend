-- =============================================================================
-- The customer a job is for
-- =============================================================================
-- WHAT CHANGES
--   · customers — a new table: who the factory makes things for.
--   · production_orders.customer_id — nullable FK to customers(id).
--   · sample_orders.customer_id     — nullable FK to customers(id).
--
-- WHY AT ALL
--   Nothing in this schema said who an order was for. The customer lived in the
--   order's free-text name, differently spelled every time, so "everything we
--   made for this customer" was not a question the data could answer, and a
--   customer's tax id or e-mail address had nowhere to live at all.
--
-- WHY NULLABLE ON BOTH ORDERS
--   Plenty of work is for nobody outside: internal trials, tooling, stock runs,
--   and every order already in the database. NULL is their correct and permanent
--   answer, not a gap waiting to be filled, so NOT NULL would be a lie with a
--   placeholder in it. It also means this migration invents no data: no existing
--   order is guessed at.
--
-- WHY NO ON DELETE CLAUSE
--   Customers are deactivated (is_active/archived_at), never deleted — the rest
--   of this schema is built that way. NO ACTION is therefore the protective
--   default: if anybody ever does try to delete a customer with orders against
--   them, the database stops it rather than quietly cutting the history loose.
--
-- WHY THE UNIQUE INDEXES ARE PARTIAL AND CASE-INSENSITIVE
--   Both `code` and `tax_id` are optional, so most rows may hold neither, and a
--   partial index says out loud that any number of customers may go without one
--   while no two may claim the same. `code` is folded to lower case because
--   people type it — the same choice already made for products.product_code
--   (uq_products_product_code_ci) and departments.name (uq_departments_name_ic).
--   A tax id is digits, so it is compared as it stands.
--
-- WHY set_updated_at() IS REUSED, NOT REDEFINED
--   The function already exists and about thirty tables run it. It bumps
--   updated_at only when a column OTHER than updated_at actually changed, which
--   a plain `NEW.updated_at := now()` would undo — every no-op UPDATE in the
--   database would start moving a timestamp that feeds the audit trail and the
--   recalculation queues. This migration adds a trigger and touches no function.
--
-- MIGRATION IMPACT
--   · Additive only. No existing column, constraint, trigger or function is
--     altered, and no existing row changes: every order starts NULL, meaning
--     "not for an outside customer".
--   · No query breaks. Nothing selected production_orders.* or sample_orders.*
--     into a positional structure, and the new column is simply absent from
--     every existing statement.
--   · Both order tables are AUDITED, so their audit trigger begins recording
--     customer_id from the first write after this runs. No back-fill of history
--     happens or should: the column did not exist for those rows.
--   · customers is registered for auditing too, so a changed name or tax id
--     carries who changed it. The id is left to the identity sequence (at 55),
--     not hardcoded, so replaying this cannot collide with a number somebody
--     else has since taken.
--   · sample_orders has no write path in the application yet — entity and
--     repository only. The column and its FK are correct and inert until that
--     side gets a service; nothing reads or sets it before then.
--   · Every step is guarded, so replaying the migration is safe.
--   · Rollback:
--       ALTER TABLE public.sample_orders     DROP COLUMN customer_id;
--       ALTER TABLE public.production_orders DROP COLUMN customer_id;
--       DROP TABLE public.customers;
--       DELETE FROM public.audit_tables WHERE table_name = 'customers';
--     Nothing else depends on any of it.
-- =============================================================================


-- ── The table ────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.customers (
    id          bigint GENERATED ALWAYS AS IDENTITY,

    -- An in-house short code, when the company keeps one. Optional: a customer
    -- known only by name is a customer.
    code        character varying(50),
    name        character varying(255) NOT NULL,
    tax_id      character varying(50),

    website     character varying(500),
    email       character varying(255),
    phone       character varying(50),

    is_active   boolean NOT NULL DEFAULT true,
    -- Set by trigger when is_active goes false, cleared when it comes back, so
    -- "since when" is answerable and not merely "yes or no".
    archived_at timestamp with time zone,

    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_customers PRIMARY KEY (id),

    -- The same guard every other named thing here carries: a row whose name is
    -- spaces is a row nobody can find again.
    CONSTRAINT chk_customers_name_not_empty
        CHECK (length(btrim(name)) > 0)
);


-- ── Indexes on customers ─────────────────────────────────────────────────────

CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_code_ci
    ON public.customers (lower((code)::text))
    WHERE code IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_tax_id
    ON public.customers (tax_id)
    WHERE tax_id IS NOT NULL;

-- Ordering the full list.
CREATE INDEX IF NOT EXISTS idx_customers_name
    ON public.customers (name);

/*
 * The picker: active customers, by name. Indexed ON NAME rather than on id — a
 * partial index over id alone can answer "does an active one exist" and little
 * else anybody asks, while this one serves the query the screens really run,
 * WHERE is_active ORDER BY name, without a sort.
 */
CREATE INDEX IF NOT EXISTS idx_customers_active_name
    ON public.customers (name)
    WHERE is_active;


-- ── Triggers on customers ────────────────────────────────────────────────────
-- The numeric prefixes are the firing order: PostgreSQL runs same-event triggers
-- in name order, and clearing archived_at (01) must happen before the rule that
-- sets it (02). Both functions and set_updated_at (03) already exist.

DROP TRIGGER IF EXISTS trg_01_customers_clear_archive_on_reactivate ON public.customers;
CREATE TRIGGER trg_01_customers_clear_archive_on_reactivate
    BEFORE UPDATE ON public.customers
    FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();

DROP TRIGGER IF EXISTS trg_02_customers_archived_at ON public.customers;
CREATE TRIGGER trg_02_customers_archived_at
    BEFORE UPDATE ON public.customers
    FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_customers_updated_at ON public.customers;
CREATE TRIGGER trg_03_customers_updated_at
    BEFORE UPDATE ON public.customers
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


-- ── Auditing customers ───────────────────────────────────────────────────────
-- audit_trigger_fn resolves the table by NAME against audit_tables, so the
-- registration has to exist before the trigger can record anything.

INSERT INTO public.audit_tables (table_name)
SELECT 'customers'
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables WHERE table_name = 'customers'
);

DROP TRIGGER IF EXISTS trg_audit_logs_customers ON public.customers;
CREATE TRIGGER trg_audit_logs_customers
    AFTER INSERT OR DELETE OR UPDATE ON public.customers
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


-- ── The link from a production order ─────────────────────────────────────────

ALTER TABLE public.production_orders
    ADD COLUMN IF NOT EXISTS customer_id bigint;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_production_orders_customer'
    ) THEN
        ALTER TABLE public.production_orders
            ADD CONSTRAINT fk_production_orders_customer
            FOREIGN KEY (customer_id) REFERENCES public.customers(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_production_orders_customer
    ON public.production_orders (customer_id)
    WHERE customer_id IS NOT NULL;


-- ── The link from a sample order ─────────────────────────────────────────────

ALTER TABLE public.sample_orders
    ADD COLUMN IF NOT EXISTS customer_id bigint;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_sample_orders_customer'
    ) THEN
        ALTER TABLE public.sample_orders
            ADD CONSTRAINT fk_sample_orders_customer
            FOREIGN KEY (customer_id) REFERENCES public.customers(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sample_orders_customer
    ON public.sample_orders (customer_id)
    WHERE customer_id IS NOT NULL;


-- ── What the columns mean ────────────────────────────────────────────────────

COMMENT ON TABLE public.customers IS
    'Who the factory makes things for. Deactivated, never deleted: orders reference the customer they were made for and that history has to survive.';

COMMENT ON COLUMN public.customers.code IS
    'Optional in-house short code. Unique case-insensitively among the customers that have one.';

COMMENT ON COLUMN public.customers.tax_id IS
    'Optional tax identification number. Unique among the customers that have one.';

COMMENT ON COLUMN public.production_orders.customer_id IS
    'The customer this order is for, when it is for one. NULL means internal work — a trial, tooling, a stock run — and is a permanent answer, not a gap.';

COMMENT ON COLUMN public.sample_orders.customer_id IS
    'The customer these samples are for, when they are for one. NULL means internal work and is a permanent answer, not a gap.';
