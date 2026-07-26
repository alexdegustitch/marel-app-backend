-- Production hardening for DB-driven recalculation queues.
BEGIN;

ALTER TABLE daily_report_recalc_queue
    ADD COLUMN IF NOT EXISTS stuck_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_stuck_at timestamptz;

ALTER TABLE monthly_report_recalc_queue
    ADD COLUMN IF NOT EXISTS stuck_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_stuck_at timestamptz;

-- Keep a single logical daily job per shift key.
DELETE FROM daily_report_recalc_queue d
USING daily_report_recalc_queue x
WHERE d.work_shift_id = x.work_shift_id
  AND d.id < x.id;

-- Keep a single logical monthly job per employee/period key.
DELETE FROM monthly_report_recalc_queue m
USING monthly_report_recalc_queue x
WHERE m.employee_id = x.employee_id
  AND m.report_year = x.report_year
  AND m.report_month = x.report_month
  AND m.id < x.id;

ALTER TABLE daily_report_recalc_queue
    ADD CONSTRAINT uq_daily_recalc_shift UNIQUE (work_shift_id);

ALTER TABLE monthly_report_recalc_queue
    ADD CONSTRAINT uq_monthly_recalc_emp_period UNIQUE (employee_id, report_year, report_month);

CREATE INDEX IF NOT EXISTS idx_daily_recalc_status_requested_id
    ON daily_report_recalc_queue (status, requested_at, id);
CREATE INDEX IF NOT EXISTS idx_monthly_recalc_status_requested_id
    ON monthly_report_recalc_queue (status, requested_at, id);

COMMIT;
