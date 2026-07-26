-- Adds a manual working-day override to work_calendar_days (true = force working
-- regardless of day_type, e.g. a worked holiday; false = force non-working, e.g.
-- an unworked Saturday; NULL = use day_type as before) and an is_active flag on
-- bonus_eligibility_rules marking whether that Saturday-of-month is actually
-- worked per the calendar, so the UI can gray out non-working Saturdays.

ALTER TABLE work_calendar_days
    ADD COLUMN IF NOT EXISTS working_override BOOLEAN;

ALTER TABLE bonus_eligibility_rules
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
