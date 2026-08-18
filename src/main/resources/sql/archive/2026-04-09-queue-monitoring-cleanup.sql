-- Monitoring and cleanup indexes for DB-driven recalc queues.
BEGIN;

-- Speeds up batched DONE cleanup scans ordered by processed_at.
CREATE INDEX IF NOT EXISTS idx_daily_recalc_done_processed_id
    ON daily_report_recalc_queue (processed_at, id)
    WHERE status = 'DONE';

CREATE INDEX IF NOT EXISTS idx_monthly_recalc_done_processed_id
    ON monthly_report_recalc_queue (processed_at, id)
    WHERE status = 'DONE';

-- Keeps oldest-pending latency checks efficient.
CREATE INDEX IF NOT EXISTS idx_daily_recalc_pending_requested
    ON daily_report_recalc_queue (requested_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_monthly_recalc_pending_requested
    ON monthly_report_recalc_queue (requested_at)
    WHERE status = 'PENDING';

COMMIT;

