-- =============================================================================
-- Repair: customers as the migration meant it, and set_updated_at() as it was
-- =============================================================================
-- WHY THIS EXISTS
--   V12 creates `customers` with CREATE TABLE IF NOT EXISTS. On a database where
--   the table had ALREADY been made by hand, that skipped silently — and then
--   went on to add every index, trigger and foreign key around it. The result is
--   a table that is neither shape: no `archived_at`, no name check, the
--   hand-made constraints still in place, and the new ones layered on top.
--
--   The application refuses to start on it, which is the good outcome:
--
--     Schema validation: missing column [archived_at] in table [customers]
--
--   A migration that says IF NOT EXISTS about a whole table is asserting "some
--   table by this name is good enough", which is not what anybody means. There
--   is no way to fix that in V12 — it is recorded as applied and its checksum is
--   part of the history — so the convergence happens here.
--
-- THE ONE THAT MATTERS MORE
--   The hand-made script also carried `CREATE OR REPLACE FUNCTION
--   set_updated_at()`, and it ran. That function is SHARED by about thirty
--   tables — absence_records, employees, production_orders, sample_orders,
--   payroll tables — and the replacement dropped its guard:
--
--     -- what it should be
--     IF (to_jsonb(NEW) - 'updated_at') IS DISTINCT FROM
--        (to_jsonb(OLD) - 'updated_at') THEN NEW.updated_at := now(); END IF;
--
--     -- what it became
--     NEW.updated_at = CURRENT_TIMESTAMP;
--
--   Without the guard every UPDATE moves updated_at, including one that changes
--   nothing. `updated_at` is read by the audit trail and by the recalculation
--   queues, so the damage is not cosmetic: work is queued for rows that did not
--   change, and "when did this last change" stops being true across the whole
--   database. Nothing on any screen shows it. Restored below, verbatim from V1.
--
-- MIGRATION IMPACT
--   · On a database built purely from migrations this is a NO-OP: every step is
--     guarded, and the function is replaced with the body it already has. That
--     is deliberate — the two kinds of database have to end up identical, and
--     the only way to be sure is for this to be safe on both.
--   · No row of `customers` is touched. Where the repair is needed the table is
--     empty; where it is not needed there is nothing to touch. `archived_at`
--     arrives NULL, which is what "not archived" means, so no customer is
--     retroactively archived.
--   · No other table, column or trigger changes. production_orders.customer_id
--     and sample_orders.customer_id were added correctly by V12 either way.
--   · The dropped objects are all SUPERSEDED, never merely unwanted — each one
--     has a replacement already in place, named below.
--   · Rollback: none needed. Every step moves the schema toward what V12
--     describes, and V12 is the definition.
-- =============================================================================


-- ── The shared touch function, as V1 wrote it ────────────────────────────────

CREATE OR REPLACE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
	IF (to_jsonb(NEW) - 'updated_at') 
	   IS DISTINCT FROM 
	   (to_jsonb(OLD) - 'updated_at') THEN
	  NEW.updated_at := now();
	END IF;
	return new;
end;
$$;


-- ── The column the application validates against ─────────────────────────────

ALTER TABLE public.customers
    ADD COLUMN IF NOT EXISTS archived_at timestamp with time zone;


-- ── The guard every other named thing here carries ───────────────────────────
-- A row whose name is spaces is a row nobody can find again.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_customers_name_not_empty'
    ) THEN
        ALTER TABLE public.customers
            ADD CONSTRAINT chk_customers_name_not_empty
            CHECK (length(btrim(name)) > 0);
    END IF;
END $$;


-- ── Superseded objects from the hand-made script ─────────────────────────────

/*
 * The duplicate touch trigger. The table carried BOTH this and
 * trg_03_customers_updated_at, running the same function twice per UPDATE — and
 * the un-numbered name sorts before trg_01/trg_02, so it also ran ahead of the
 * archival triggers it has nothing to do with. trg_03 stays.
 */
DROP TRIGGER IF EXISTS trg_customers_set_updated_at ON public.customers;

/*
 * A partial index over `id` alone. It can answer "does an active customer
 * exist" and little else anybody asks; idx_customers_active_name serves the
 * query the screens actually run, WHERE is_active ORDER BY name.
 */
DROP INDEX IF EXISTS public.idx_customers_active;

/*
 * Case-SENSITIVE uniqueness on a code people type, which would have let "Acme"
 * and "ACME" be two customers. uq_customers_code_ci is already in place and is
 * strictly stronger.
 */
ALTER TABLE public.customers
    DROP CONSTRAINT IF EXISTS uq_customers_code;

/*
 * The tax id is the one place the two shapes collided by NAME: V12's partial
 * unique index could not be created because a constraint already held the name.
 * Dropping the constraint frees it, and the index is created below — so a
 * repaired database and a fresh one end up with the same object, not merely
 * with the same effect.
 */
ALTER TABLE public.customers
    DROP CONSTRAINT IF EXISTS uq_customers_tax_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_tax_id
    ON public.customers (tax_id)
    WHERE tax_id IS NOT NULL;


-- ── What the column means ────────────────────────────────────────────────────

COMMENT ON COLUMN public.customers.archived_at IS
    'When the customer was deactivated; NULL while active. Written by trigger, never by the application.';
