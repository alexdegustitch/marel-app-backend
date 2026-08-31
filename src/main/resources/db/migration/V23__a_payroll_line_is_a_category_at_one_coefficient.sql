-- =============================================================================
-- A payroll line is a category at one coefficient
-- =============================================================================
-- WHAT CHANGES
--   payroll_run_item_categories.uq_pric_item_category — the coefficient joins
--     the key, as it already has on the daily and monthly report rows.
--
-- WHY IT IS NOT IN V22
--   V22 had already been applied when this turned out to be needed, and editing
--   an applied migration changes its checksum: Flyway would refuse to start the
--   application rather than silently accept a file that no longer matches what
--   it ran. So the correction arrives as its own migration, which is what
--   Flyway's model asks for.
--
-- WHY THE KEY HAS TO GIVE
--   "One line per category per payroll item" was exactly right while a category
--   could only ever be worth one thing. With a coefficient somebody may type on
--   a single operation, a month can hold the same category twice — four hours of
--   J as two at 1.10 and two at 1.20 — and the second line is not a duplicate of
--   the first. It is the other half of somebody's work, and refusing it here
--   would either fail the payroll or quietly leave those minutes unpaid.
--
--   The payslip still shows the category ONCE. That folding happens when the
--   document is drawn, not by throwing a row away here.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing. The new key is the old key plus a column, so every row that was
--   unique before is unique now; no row can collide that did not collide
--   already. Locked payrolls are not rebuilt and are untouched.
-- =============================================================================

ALTER TABLE public.payroll_run_item_categories
    DROP CONSTRAINT uq_pric_item_category;

ALTER TABLE public.payroll_run_item_categories
    ADD CONSTRAINT uq_pric_item_category
        UNIQUE (payroll_run_item_id, work_code_category_id, category_coefficient_snapshot);
