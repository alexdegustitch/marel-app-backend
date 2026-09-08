-- =============================================================================
-- A product type gains the standard it is built and tested to
-- =============================================================================
-- WHAT CHANGES
--   One new nullable column on product_types.
--
--     · product_types.standard — the norm(s)/standard(s) the type is made and
--       tested to (e.g. "SRPS N.F4.101 | EN 61238-1-1 | EN 61238-1-3"). Free
--       text; several standards are listed together separated by " | " in the
--       source catalogue, so no length limit is imposed (text, like description).
--
-- WHY ON THE TYPE, NOT THE PRODUCT
--   In the catalogue the standard is a property of the kind of product (the
--   type), shared by every article of that type — the same place code,
--   description and note already live. Modelling it on the type keeps one answer
--   per type instead of repeating the same string on every product row.
--
-- MIGRATION IMPACT
--   · Additive only. No existing column, constraint, trigger or function is
--     altered; every existing product_types row starts with standard NULL,
--     meaning "not recorded", a permanent and correct answer.
--   · No query breaks. Nothing selected product_types.* positionally, and the
--     new column is simply absent from every existing statement.
--   · product_types is already audited (registered in V34), so the new column
--     records who changed it from the first write. No back-fill of history.
--   · Guarded (ADD COLUMN IF NOT EXISTS); replaying is safe.
--   · Rollback:
--       ALTER TABLE public.product_types DROP COLUMN standard;
-- =============================================================================

ALTER TABLE public.product_types
    ADD COLUMN IF NOT EXISTS standard text;

COMMENT ON COLUMN public.product_types.standard IS
    'The standard(s) the type is made and tested to (e.g. "SRPS N.F4.101 | EN 61238-1-1"). Free text; several may be listed together. NULL means not recorded.';
