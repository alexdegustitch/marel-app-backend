-- Composite index for the hot (employee_id, work_date) access pattern on daily_reports.
-- Used by:
--   * countPreviousDaysWithInsufficientBonusMinutes (runs on every weekend recalc)
--   * findByEmployee_IdAndWorkDateBetween (charts/reports, user-facing)
--   * the monthly summary aggregation (employee_id prefix)
-- Without it these do a sequential scan; cheap while the table is small, but daily_reports
-- grows unbounded (one row per employee per shift per day).
-- On a large production table, create this CONCURRENTLY to avoid a write lock.
CREATE INDEX IF NOT EXISTS idx_daily_reports_employee_work_date
    ON daily_reports (employee_id, work_date);
