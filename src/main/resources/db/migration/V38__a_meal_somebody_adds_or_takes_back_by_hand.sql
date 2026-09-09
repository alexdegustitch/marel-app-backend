-- =============================================================================
-- A meal somebody adds or takes back by hand
-- =============================================================================
-- WHAT CHANGES
--   daily_reports gains four columns:
--     meals_manual_delta  integer NOT NULL DEFAULT 0  — the hand correction,
--                         added to the computed meals_count (may be negative)
--     meals_manual_note   varchar(255)                — why, in the admin's words
--     meals_manual_at     timestamptz                 — when the hand moved
--     meals_manual_by     bigint → users(id)          — whose hand it was
--
-- WHY A DELTA AND NOT AN OVERRIDE VALUE
--   meals_count is recalc-owned: every daily rebuild recomputes it from the
--   category minutes and writes it back. An absolute override would either be
--   clobbered by the next recalculation or would have to freeze the computed
--   figure — and a frozen figure silently stops following the shift it
--   describes. A delta survives every rebuild untouched (the recalc never
--   writes these columns) and keeps both truths visible:
--
--     computed 2, delta -1  →  paid 1, and the karton can say WHY it is 1.
--
--   The effective figure is GREATEST(0, meals_count + meals_manual_delta),
--   computed in Java (DailyReport.getEffectiveMealsCount) — never stored, so
--   it can never drift from its inputs.
--
-- WHO SUMS IT
--   MonthlyRecalcService now sums the EFFECTIVE count into
--   monthly_reports.meal_allowance_num, which is where the payroll's
--   MealAllowanceCalculator already reads. No payroll change is needed.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing. The default 0 means every existing report keeps exactly the
--   figure it had; NULL note/at/by means "never touched by hand".
--
--   · Rollback: ALTER TABLE daily_reports
--       DROP COLUMN meals_manual_delta, DROP COLUMN meals_manual_note,
--       DROP COLUMN meals_manual_at,    DROP COLUMN meals_manual_by;
-- =============================================================================

ALTER TABLE daily_reports
    ADD COLUMN meals_manual_delta integer NOT NULL DEFAULT 0,
    ADD COLUMN meals_manual_note  varchar(255),
    ADD COLUMN meals_manual_at    timestamptz,
    ADD COLUMN meals_manual_by    bigint REFERENCES users (id);

COMMENT ON COLUMN daily_reports.meals_manual_delta IS
    'Hand correction added to the computed meals_count; effective = GREATEST(0, meals_count + meals_manual_delta). Never written by recalc.';
COMMENT ON COLUMN daily_reports.meals_manual_note IS
    'Why the meal count was corrected by hand.';
COMMENT ON COLUMN daily_reports.meals_manual_at IS
    'When the manual meal correction was last changed.';
COMMENT ON COLUMN daily_reports.meals_manual_by IS
    'User who last changed the manual meal correction.';
