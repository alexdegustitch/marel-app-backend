-- =============================================================================
-- One rule was written three times
-- =============================================================================
-- WHAT WAS WRONG
--   Adding a second "Isplaćeno" still failed with
--   uq_payroll_adjustmentS_item_category after V59: the baseline carries the
--   one-row-per-(item, category) rule TWICE — once as
--   uq_payroll_adjustment_item_category and once more, identically, with the
--   plural name — and a long-lived database can carry it a third time under a
--   Hibernate-generated name (ukal8innxq…) from the ddl-auto=update era. V59
--   replaced exactly one of them; the others went on refusing what the partial
--   index now allows.
--
-- WHAT THIS DOES
--   Drops EVERY remaining full unique constraint over exactly
--   (payroll_run_item_id, payroll_adjustment_category_id), whatever it is
--   named, so environments that drifted are cleaned the same as fresh ones.
--   The rule itself is not weakened: V59's partial index
--   uq_payroll_adjustment_item_category (WHERE NOT is_custom) stays and is the
--   single statement of it — system rows unique per category, hand-added rows
--   free to repeat one.
--
--   No new column enters the key on purpose: a custom row is OUTSIDE the
--   uniqueness rule, not another dimension of it.
--
--   · Rollback (after deleting custom rows):
--       ALTER TABLE payroll_adjustments
--           ADD CONSTRAINT uq_payroll_adjustments_item_category
--           UNIQUE (payroll_run_item_id, payroll_adjustment_category_id);
-- =============================================================================

DO $$
DECLARE
    con record;
BEGIN
    FOR con IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'payroll_adjustments'::regclass
          AND c.contype = 'u'
          AND (
              SELECT array_agg(a.attname ORDER BY a.attname)
              FROM unnest(c.conkey) AS k
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k
          ) = ARRAY['payroll_adjustment_category_id', 'payroll_run_item_id']::name[]
    LOOP
        EXECUTE format('ALTER TABLE payroll_adjustments DROP CONSTRAINT %I', con.conname);
        RAISE NOTICE 'Dropped duplicate unique constraint % on payroll_adjustments', con.conname;
    END LOOP;
END $$;
