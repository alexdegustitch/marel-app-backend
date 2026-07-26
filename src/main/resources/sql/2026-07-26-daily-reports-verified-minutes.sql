-- Verified (coefficient-weighted) time on the daily report.
--
-- WHY A NEW COLUMN
-- No existing column holds this quantity. total_weighted_norm_minutes and
-- total_approved_minutes are weighted by APPROVED PERFORMANCE (efficiency);
-- verified time weights covered intervals by the PL/PLB coefficient from
-- work_code_categories.norm_multiplier. Reusing either column would conflate two
-- different measures and change payroll figures, so the new value is stored
-- alongside rather than on top.
--
-- BACKWARD COMPATIBILITY
-- All three columns are NULLABLE with no default and nothing reads them yet
-- besides the new code paths. Existing rows keep their historical values and are
-- NOT backfilled: a report shows verified time only once it is recalculated
-- through the normal triggers. Locked or finalized payroll periods are therefore
-- untouched, and payroll_run_items keep their own snapshot columns regardless.
--
-- No backfill is included by design. Recomputing historical reports would rewrite
-- audited business data and must be an explicit, separately approved operation.
--
-- Re-runnable: IF NOT EXISTS on every statement.

ALTER TABLE public.daily_reports
    ADD COLUMN IF NOT EXISTS total_verified_minutes numeric(38,4);

ALTER TABLE public.daily_reports
    ADD COLUMN IF NOT EXISTS total_pl_minutes integer;

ALTER TABLE public.daily_reports
    ADD COLUMN IF NOT EXISTS total_plb_minutes integer;

COMMENT ON COLUMN public.daily_reports.total_verified_minutes IS
    'Sum over non-overlapping covered intervals of duration x PL/PLB coefficient. '
    'Separate from total_weighted_norm_minutes, which is weighted by approved performance. '
    'NULL until the report is recalculated.';

COMMENT ON COLUMN public.daily_reports.total_pl_minutes IS
    'Covered minutes classified PL (fewer than three parallel-capable logs active).';

COMMENT ON COLUMN public.daily_reports.total_plb_minutes IS
    'Covered minutes classified PLB (three or more parallel-capable logs simultaneously active).';
