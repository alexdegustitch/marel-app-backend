-- Drop redundant partial unique index on monthly_report_recalc_queue.
--
-- uq_monthly_queue_pending (UNIQUE WHERE status = 'PENDING') is a strict subset of
-- uq_monthly_recalc_active_period (UNIQUE WHERE status IN ('PENDING', 'IN_PROGRESS')),
-- which already enforces the same uniqueness for every row the older index covers.
--
-- RecalcQueueService.enqueueMonthlyJob's INSERT ... ON CONFLICT (employee_id, report_year,
-- report_month) WHERE (status IN ('PENDING', 'IN_PROGRESS')) DO UPDATE only names
-- uq_monthly_recalc_active_period as its conflict arbiter. Under concurrent enqueue attempts
-- for the same employee+month (more frequent since daily recalcs now cascade to a week's
-- Saturday/Sunday shifts), the insert could also collide with the OTHER, non-arbiter index
-- (uq_monthly_queue_pending), which Postgres does not resolve via ON CONFLICT — producing a
-- hard "duplicate key value violates unique constraint" error instead of the intended upsert.
--
-- No data loss, no change in enforced uniqueness: any row that would have violated
-- uq_monthly_queue_pending already violates uq_monthly_recalc_active_period.

BEGIN;

DROP INDEX IF EXISTS uq_monthly_queue_pending;

COMMIT;
