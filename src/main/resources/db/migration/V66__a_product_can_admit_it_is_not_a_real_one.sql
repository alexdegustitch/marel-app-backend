-- =============================================================================
-- A product can admit it is not a real one
-- =============================================================================
-- WHAT CHANGES
--   products gains one column:
--     is_virtual  boolean NOT NULL DEFAULT false
--   TRUE marks an INTERNI proizvod — a catalogue entry that exists so internal
--   work has a product/operation pair to be logged under (Higijena with
--   "uređivanje radnog prostora" and the like), while nothing is ever
--   produced, ordered or delivered against it.
--
-- WHAT READS IT
--   · Order creation refuses a virtual product on a line item — production and
--     sample orders both. The guard lives in the services; the column is the
--     fact they read.
--   · Product pickers in the order forms filter virtual products out; the
--     karton's product picker keeps them, because the karton is exactly where
--     internal work is logged.
--   · The products board shows the flag as a badge and a filter.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing moves. Every existing product starts as a real one; marking the
--   internal ones is an administrator's decision made in the UI, not a guess a
--   migration makes from names. (The full-day absence pairs that LOOKED like
--   this are gone a migration earlier — V65 made those operations standalone
--   and archived their technical products.)
--
--   Guarded; replaying is safe.
--
-- Rollback:
--   ALTER TABLE public.products DROP COLUMN is_virtual;
-- =============================================================================

ALTER TABLE public.products
    ADD COLUMN IF NOT EXISTS is_virtual boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public.products.is_virtual IS
    'TRUE for an INTERNI proizvod — a holder for internal work (e.g. Higijena) logged on kartoni. Never allowed on a production or sample order line item; order pickers filter it out, the karton keeps it.';
