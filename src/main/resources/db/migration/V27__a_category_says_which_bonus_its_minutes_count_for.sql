-- =============================================================================
-- A category says which bonus its minutes count for
-- =============================================================================
-- WHAT CHANGES
--   work_code_categories — affects_weekend_bonus and affects_monthly_bonus, both
--     backfilled to reproduce today's behaviour exactly.
--   monthly_reports — monthly_bonus_eligible_minutes, backfilled from
--     total_work_minutes, which is what the monthly bonus measures today.
--
-- WHY TWO FLAGS AND NOT ONE
--   The two bonuses are different questions asked of different numbers. The
--   weekend bonus asks whether EVERY day of the week reached 180 minutes; the
--   monthly bonus asks how many hours the month came to. A category can
--   reasonably count for one and not the other, and until now neither could be
--   said at all: both were decided in Java by type = 'WORK'.
--
--   affects_bonus already existed and is DEAD in a fuller sense than it looks:
--   it is not mapped on the entity, never read anywhere, and not even the source
--   of the snapshot that shares its name — payroll_run_item_categories
--   .category_affects_bonus_snapshot is set from "WORK".equals(type) and gates a
--   third thing again, the per-line performance bonus. The column is left alone
--   rather than repurposed, because a dead column quietly given a new meaning is
--   worse than a dead column.
--
-- WHY THE BACKFILL IS type = 'WORK' AND affects_bonus
--   Because that is precisely what the code does today, and a migration that
--   changes behaviour while claiming to add a switch is the worst kind. Every
--   WORK category carries affects_bonus = true, so in practice this reads "the
--   work categories, all of them" — but written as the conjunction it stays
--   correct on a database where somebody has already turned one off.
--
--   Absence and sick leave get FALSE on both, which is also today's behaviour:
--   bonus_eligible_minutes counts source_type = 'WORK' and nothing else. Since
--   V25 an absence produces a category row of its own, so leaving these TRUE
--   would have started paying a weekend bonus for days nobody worked.
--
-- WHY monthly_bonus_eligible_minutes IS BACKFILLED AND NOT LEFT AT ZERO
--   MonthlyBonusCalculator reads it from this release on. A column defaulting to
--   zero would have every existing month measure zero hours until something
--   happened to recalculate it — and a monthly bonus quietly reading nothing is
--   money somebody does not get paid. total_work_minutes is exactly the figure
--   the calculator used before, so the backfill reproduces every past answer.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Which bonus a category's minutes count for
-- -----------------------------------------------------------------------------

ALTER TABLE public.work_code_categories
    ADD COLUMN affects_weekend_bonus boolean DEFAULT true NOT NULL,
    ADD COLUMN affects_monthly_bonus boolean DEFAULT true NOT NULL;

UPDATE public.work_code_categories
SET affects_weekend_bonus = (type = 'WORK' AND affects_bonus),
    affects_monthly_bonus = (type = 'WORK' AND affects_bonus);

COMMENT ON COLUMN public.work_code_categories.affects_weekend_bonus IS
    'Do these minutes count towards the 180 a day the weekend bonus needs. See DailyRecalcService.';
COMMENT ON COLUMN public.work_code_categories.affects_monthly_bonus IS
    'Do these minutes count towards the hours the monthly bonus is measured on. See MonthlyBonusCalculator.';


-- -----------------------------------------------------------------------------
-- 2. The month's own bonus measure
-- -----------------------------------------------------------------------------

ALTER TABLE public.monthly_reports
    ADD COLUMN monthly_bonus_eligible_minutes integer DEFAULT 0 NOT NULL;

-- What the calculator measured before this column existed. Every month keeps the
-- answer it already had, and the first recalculation recomputes it from the
-- categories rather than inheriting this number.
UPDATE public.monthly_reports
SET monthly_bonus_eligible_minutes = COALESCE(total_work_minutes, 0);

COMMENT ON COLUMN public.monthly_reports.monthly_bonus_eligible_minutes IS
    'Minutes of categories with affects_monthly_bonus. The manual corrections are added by the calculator, not stored here.';
