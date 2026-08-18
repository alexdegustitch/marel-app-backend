-- Ensure only one active monthly queue job per employee/period while keeping historical DONE/FAILED rows.
BEGIN;

ALTER TABLE monthly_report_recalc_queue
    DROP CONSTRAINT IF EXISTS uq_monthly_recalc_emp_period;

-- Deduplicate active rows before creating a partial unique index.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY employee_id, report_year, report_month
               ORDER BY requested_at DESC NULLS LAST, id DESC
           ) AS rn
    FROM monthly_report_recalc_queue
    WHERE status IN ('PENDING', 'IN_PROGRESS')
)
DELETE FROM monthly_report_recalc_queue q
USING ranked r
WHERE q.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_monthly_recalc_active_period
    ON monthly_report_recalc_queue (employee_id, report_year, report_month)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

COMMIT;

