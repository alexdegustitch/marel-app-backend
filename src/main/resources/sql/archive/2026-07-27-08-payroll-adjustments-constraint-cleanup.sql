-- =============================================================================
-- payroll_adjustments — remove duplicated constraints, protect history
-- =============================================================================
-- Three pre-existing problems, none of them introduced by this feature, all
-- found while tracing how payroll adjustment names reach the PDF.
--
-- 1. TWO IDENTICAL UNIQUE CONSTRAINTS on (payroll_run_item_id,
--    payroll_adjustment_category_id):
--       ukal8innxq2iwiero5sfbtorlxf      Hibernate-generated, from ddl-auto
--       uq_payroll_adjustments_item_category   the intentional, named one
--    Two identical unique indexes cost two index writes per row and produce a
--    random-looking constraint name in violation errors. The generated one goes;
--    the named one stays.
--
-- 2. TWO CHECKS ON THE SAME note COLUMN:
--       chk_payroll_adjustments_note_not_empty    note IS NULL OR trim <> ''
--       chk_payroll_adjustments_reason_not_empty  trim(note) <> ''
--    The second is a leftover from when the column was called "reason". Its
--    name no longer describes any column on the table. The first is the correct
--    formulation (NULL is a legal "no note") and is kept.
--
-- 3. FK payroll_adjustment_category_id -> payroll_adjustment_categories
--    ON DELETE CASCADE. Deleting a master category would silently delete every
--    historical payroll adjustment that ever used it, including adjustments
--    belonging to LOCKED payroll runs. That is data loss disguised as a
--    referential action. Changed to RESTRICT.
--
--    Verified safe before changing it:
--      - PayrollAdjustmentCategoryService exposes no hard delete; the admin UI
--        deactivates (is_active = false, archived_at = now()), which the archive
--        model already supports.
--      - No test deletes a payroll_adjustment_category.
--    A category with historical adjustments must now be archived rather than
--    deleted, which is what the rest of the reference data already does.
--
-- Re-runnable: every statement is guarded on catalogue state.
-- =============================================================================

-- 1. Drop the Hibernate-generated duplicate, keep the named one. Only ever drops
--    the generated name, so re-running after a fresh ddl-auto has recreated it is
--    still correct and never removes the intentional constraint.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_payroll_adjustments_item_category')
       AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukal8innxq2iwiero5sfbtorlxf') THEN
        ALTER TABLE payroll_adjustments DROP CONSTRAINT ukal8innxq2iwiero5sfbtorlxf;
    END IF;
END $$;

-- 2. Drop the misnamed duplicate note check, keeping the NULL-tolerant one.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_payroll_adjustments_note_not_empty')
       AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_payroll_adjustments_reason_not_empty') THEN
        ALTER TABLE payroll_adjustments DROP CONSTRAINT chk_payroll_adjustments_reason_not_empty;
    END IF;
END $$;

-- 3. CASCADE -> RESTRICT on the master-category FK.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_payroll_adjustments_pac'
          AND confdeltype = 'c'   -- 'c' = CASCADE
    ) THEN
        ALTER TABLE payroll_adjustments DROP CONSTRAINT fk_payroll_adjustments_pac;
        ALTER TABLE payroll_adjustments
            ADD CONSTRAINT fk_payroll_adjustments_pac
            FOREIGN KEY (payroll_adjustment_category_id)
            REFERENCES payroll_adjustment_categories (id) ON DELETE RESTRICT;
    END IF;
END $$;

COMMENT ON CONSTRAINT fk_payroll_adjustments_pac ON payroll_adjustments IS
    'RESTRICT, not CASCADE: historical payroll adjustments must survive the retirement of a master category. Retire categories with is_active = false / archived_at.';
