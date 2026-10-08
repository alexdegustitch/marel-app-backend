-- =============================================================================
-- A settlement line knows its place
-- =============================================================================
-- WHAT CHANGES
--   One column, payroll_adjustments.sort_order: where THIS line sits inside its
--   section on THIS payroll. NULL — which every existing row keeps — means the
--   category's own sort_order, exactly what the document showed yesterday. So
--   the default order stays the šifarnik's: Rata (10), Telefon za prethodni
--   mesec (20), Isplaćeno (30), Isplaćeno drugi deo (40) — and a reorder is a
--   per-payroll decision that touches nobody else's month.
--
-- WHY PER LINE AND NOT PER CATEGORY
--   Two custom "Isplaćeno" lines share one category, so the category's number
--   cannot put one above the other. And a reorder of March must not rearrange
--   February: the šifarnik is the default, the line is the decision.
--
--   sort_order_snapshot stays what it was — the category's number frozen at
--   lock time. This column is the LIVE order; the snapshot is history.
--
--   · Rollback: ALTER TABLE payroll_adjustments DROP COLUMN sort_order;
-- =============================================================================

ALTER TABLE payroll_adjustments
    ADD COLUMN sort_order integer;
