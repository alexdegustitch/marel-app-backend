BEGIN;

ALTER TABLE monthly_reports
    ADD COLUMN IF NOT EXISTS total_absence_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_sick_leave_minutes integer NOT NULL DEFAULT 0;

UPDATE monthly_reports
SET total_absence_minutes = COALESCE(total_absence_paid_minutes, 0) + COALESCE(total_absence_unpaid_minutes, 0),
    total_sick_leave_minutes = COALESCE(total_sick_leave_paid_minutes, 0) + COALESCE(total_sick_leave_unpaid_minutes, 0);

COMMIT;

