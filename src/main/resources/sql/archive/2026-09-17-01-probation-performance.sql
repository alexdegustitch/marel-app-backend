-- =============================================================================
-- Work done on probation is credited at 100 %
-- =============================================================================
-- THE RULE
-- While an employee is on probation their PAID performance is 100 %, whatever
-- they actually produced. Norm 40/h and they made 35 — 100 %. They made 50 —
-- 100 %. The ACTUAL figure is still recorded and still shown: daily_reports,
-- monthly_reports and payroll_run_items all already carry performance_rate
-- (what happened) beside approved_performance_rate (what is paid), and only the
-- second one changes.
--
-- THE CEILING STILL WINS IF IT IS LOWER (owner's rule). So the paid rate is
-- min(100, app_settings.max_efficiency_percent) — 100 is substituted for the
-- measured rate and the existing ceiling is then applied exactly as before,
-- rather than probation overriding it. With nothing configured the ceiling is
-- 100 anyway, so in practice the two agree.
--
-- IN FORCE ALWAYS, not from a date, and the owner has accepted that
-- recalculating an earlier month will change what it pays.
--
-- WHY A COLUMN AT ALL
-- approved_performance_rate = 100 does not say WHY. An employee who genuinely
-- hit exactly 100 % and one who was on probation are indistinguishable in the
-- data, and "why is this shift 100 %" is a question somebody will ask about a
-- payslip. This column answers it.
--
-- ON daily_reports, not on work_shifts: daily_reports IS the computed summary of
-- a shift, rebuilt from the work logs on every recalculation, so the flag cannot
-- drift away from the employment data it was derived from. It also sits beside
-- approved_performance_rate, the number it explains, which is where the screen
-- already reads from.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE daily_reports
    ADD COLUMN IF NOT EXISTS was_probation BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN daily_reports.was_probation IS
    'TRUE when the employee was inside their probation period on this work date, so approved_performance_rate was credited at 100 % rather than measured. Derived by DailyRecalcService from ProbationPolicy on every recalculation — never entered by hand.';

-- Reading "which shifts were probation" is a filter over a month, not a lookup,
-- and the flag is false for nearly every row — so a partial index on the true
-- ones is small and answers exactly that question.
CREATE INDEX IF NOT EXISTS idx_daily_reports_probation
    ON daily_reports (employee_id, work_date)
    WHERE was_probation;
