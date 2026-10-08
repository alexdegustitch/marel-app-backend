-- =============================================================================
-- A settlement somebody adds and names
-- =============================================================================
-- WHAT CHANGES
--   Two columns on payroll_adjustments, and one constraint loosened:
--
--   · display_name — the name THIS line shows on the payslip, typed by whoever
--     put it there. NULL — which every existing row keeps — means "the
--     category's name", resolved exactly as before, translations included. The
--     value is a verbatim label: a hand-typed name cannot be translated, so it
--     prints the same in every document language.
--
--   · is_custom — TRUE for a line a person added by hand on one payroll, as
--     opposed to the row-per-category set the initialisation creates. Immutable
--     in spirit: nothing ever flips it after insert.
--
--   · uq_payroll_adjustment_item_category becomes a PARTIAL unique index over
--     the non-custom rows. The one-row-per-category rule (D9) still holds for
--     everything the system creates and calculates against; only hand-added
--     lines may repeat a category, which is the whole point — a second
--     "Isplaćeno" with its own name and its own figure.
--
-- WHY display_name IS NOT BACKFILLED
--   Copying the category name onto every old row would freeze it there:
--   renaming a category, or translating it for an employee's PDF, would stop
--   reaching history. NULL-as-default keeps both working and still shows
--   exactly the category's name, which is what those rows showed yesterday.
--
-- WHY THE PARTIAL INDEX IS SAFE FOR THE CALCULATORS
--   Every per-category write the recalculation does goes through lookups that
--   now exclude custom rows (findByItemIdAndCategoryCode,
--   findByItemIdAndCalculationKey), so each still finds at most one row — the
--   system's own. Custom rows are reached only by id and summed by section.
--
--   · Rollback: ALTER TABLE payroll_adjustments
--                   DROP COLUMN display_name, DROP COLUMN is_custom;
--               DROP INDEX uq_payroll_adjustment_item_category;
--               ALTER TABLE payroll_adjustments
--                   ADD CONSTRAINT uq_payroll_adjustment_item_category
--                   UNIQUE (payroll_run_item_id, payroll_adjustment_category_id);
--               (drop the custom rows first, or the constraint cannot build)
-- =============================================================================

ALTER TABLE payroll_adjustments
    ADD COLUMN display_name varchar(120)
        CONSTRAINT chk_pa_display_name_not_blank
            CHECK (display_name IS NULL OR length(trim(display_name)) > 0),
    ADD COLUMN is_custom boolean NOT NULL DEFAULT false;

ALTER TABLE payroll_adjustments
    DROP CONSTRAINT uq_payroll_adjustment_item_category;

CREATE UNIQUE INDEX uq_payroll_adjustment_item_category
    ON payroll_adjustments (payroll_run_item_id, payroll_adjustment_category_id)
    WHERE NOT is_custom;
