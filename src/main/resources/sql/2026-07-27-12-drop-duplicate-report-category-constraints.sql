-- =============================================================================
-- Drop the duplicated unique constraints on the report-category tables
-- =============================================================================
-- Pre-existing, unrelated to compensation schemes, found while confirming that
-- daily_report_categories really is unique per (report, category) — which it is,
-- twice over:
--
--   daily_report_categories    uq_daily_report_category_report_category
--                              uq_drc                          <- duplicate
--   monthly_report_categories  uq_monthly_report_category_report_category
--                              uq_mrc                          <- duplicate
--
-- Both pairs cover exactly (report_id, work_code_category_id). Two identical
-- unique indexes cost two index writes per row for no added guarantee.
--
-- WHICH ONE IS THE REAL ONE — checked rather than guessed:
--   * the LONG names are created by 2026-03-31-report-model-refactor.sql;
--   * the LONG names are declared by the JPA entities themselves, in
--     @Table(uniqueConstraints = @UniqueConstraint(name = ...)) on
--     DailyReportCategory and MonthlyReportCategory;
--   * the SHORT names appear nowhere in the codebase.
-- So the long ones stay. Dropping them instead would have made Hibernate's
-- schema validation disagree with the database.
--
-- Guarded on both existing, so this can never leave a table with no unique
-- constraint at all.
--
-- Re-runnable.
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_daily_report_category_report_category')
       AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_drc') THEN
        ALTER TABLE daily_report_categories DROP CONSTRAINT uq_drc;
        RAISE NOTICE 'Dropped duplicate constraint uq_drc';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_monthly_report_category_report_category')
       AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_mrc') THEN
        ALTER TABLE monthly_report_categories DROP CONSTRAINT uq_mrc;
        RAISE NOTICE 'Dropped duplicate constraint uq_mrc';
    END IF;
END $$;
