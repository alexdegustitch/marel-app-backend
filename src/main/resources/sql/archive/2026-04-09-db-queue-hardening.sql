-- DB queue hardening for DB-driven workers (no in-memory task queue dependency)
BEGIN;

ALTER TABLE daily_report_recalc_queue
    ADD COLUMN IF NOT EXISTS claimed_by varchar(255),
    ADD COLUMN IF NOT EXISTS claimed_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_error text;

ALTER TABLE monthly_report_recalc_queue
    ADD COLUMN IF NOT EXISTS claimed_by varchar(255),
    ADD COLUMN IF NOT EXISTS claimed_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_error text;

-- Normalize legacy status values.
UPDATE daily_report_recalc_queue SET status = 'IN_PROGRESS' WHERE status = 'PROCESSING';
UPDATE daily_report_recalc_queue SET status = 'DONE' WHERE status = 'PROCESSED';
UPDATE monthly_report_recalc_queue SET status = 'IN_PROGRESS' WHERE status = 'PROCESSING';
UPDATE monthly_report_recalc_queue SET status = 'DONE' WHERE status = 'PROCESSED';

CREATE INDEX IF NOT EXISTS idx_daily_recalc_status_requested
    ON daily_report_recalc_queue (status, requested_at);
CREATE INDEX IF NOT EXISTS idx_daily_recalc_claimed_at
    ON daily_report_recalc_queue (claimed_at);
CREATE INDEX IF NOT EXISTS idx_monthly_recalc_status_requested
    ON monthly_report_recalc_queue (status, requested_at);
CREATE INDEX IF NOT EXISTS idx_monthly_recalc_claimed_at
    ON monthly_report_recalc_queue (claimed_at);

COMMIT;

