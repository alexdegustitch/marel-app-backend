-- =============================================================================
-- An order can be called off — and the record says who did it and when
-- =============================================================================
-- WHAT CHANGES
--   · production_orders: the status CHECK gains 'CANCELLED', and two audit
--     columns arrive — cancelled_at, cancelled_by → users(id).
--   · sample_orders: the same two audit columns. The status column is free
--     text (see SampleOrderStatus), so no CHECK to widen; the application
--     writes the lower-case 'cancelled' beside the existing 'created'/'closed'.
--
-- WHY A STATUS AND NOT AN ARCHIVE
--   A cancelled order HAPPENED — it was written, perhaps partly worked, and
--   then called off. Archiving would make it vanish from history; a terminal
--   status keeps it readable while every screen stops treating it as open.
--   Cancelling is signed with the caller's password, exactly like the
--   catalogue archives, and the signature is recorded here: an order that was
--   called off and cannot say by whom is a gap in exactly the record this
--   application exists to keep.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing rewritten. The CHECK is re-created with a superset of its values,
--   which every existing row already satisfies; the new columns are null for
--   every order that was never cancelled — null is the correct, permanent
--   answer there, not a gap.
--
--   Guarded (IF EXISTS / IF NOT EXISTS); replaying is safe.
--
-- Rollback:
--   ALTER TABLE public.production_orders DROP CONSTRAINT chk_production_orders_status;
--   ALTER TABLE public.production_orders ADD CONSTRAINT chk_production_orders_status
--       CHECK (((status)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('DELIVERED'::character varying)::text])));
--   ALTER TABLE public.production_orders DROP COLUMN cancelled_at, DROP COLUMN cancelled_by;
--   ALTER TABLE public.sample_orders DROP COLUMN cancelled_at, DROP COLUMN cancelled_by;
--   (Only valid while no row holds status CANCELLED.)
-- =============================================================================

ALTER TABLE public.production_orders
    DROP CONSTRAINT IF EXISTS chk_production_orders_status;

ALTER TABLE public.production_orders
    ADD CONSTRAINT chk_production_orders_status
    CHECK (((status)::text = ANY (ARRAY[
        ('CREATED'::character varying)::text,
        ('DELIVERED'::character varying)::text,
        ('CANCELLED'::character varying)::text])));

ALTER TABLE public.production_orders
    ADD COLUMN IF NOT EXISTS cancelled_at timestamp with time zone;

ALTER TABLE public.production_orders
    ADD COLUMN IF NOT EXISTS cancelled_by bigint;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_production_orders_cancelled_by'
    ) THEN
        ALTER TABLE public.production_orders
            ADD CONSTRAINT fk_production_orders_cancelled_by
            FOREIGN KEY (cancelled_by) REFERENCES public.users(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_production_orders_cancelled_by
    ON public.production_orders USING btree (cancelled_by);

ALTER TABLE public.sample_orders
    ADD COLUMN IF NOT EXISTS cancelled_at timestamp with time zone;

ALTER TABLE public.sample_orders
    ADD COLUMN IF NOT EXISTS cancelled_by bigint;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_sample_orders_cancelled_by'
    ) THEN
        ALTER TABLE public.sample_orders
            ADD CONSTRAINT fk_sample_orders_cancelled_by
            FOREIGN KEY (cancelled_by) REFERENCES public.users(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sample_orders_cancelled_by
    ON public.sample_orders USING btree (cancelled_by);
