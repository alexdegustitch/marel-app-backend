-- Manual migration script for report/work-log model refactor.
-- Execute in a maintenance window and validate data assumptions before running in production.

BEGIN;

-- 1) Remove obsolete table.
DROP TABLE IF EXISTS payroll_dirty_flags;

-- 2) Work logs: FK rename + new constraints.
ALTER TABLE work_logs
    RENAME COLUMN work_code_id TO work_code_category_id;

ALTER TABLE work_logs
    ALTER COLUMN work_code_category_id SET NOT NULL,
    ALTER COLUMN operation_id SET NOT NULL,
    ALTER COLUMN quantity DROP NOT NULL,
    ALTER COLUMN scrap DROP NOT NULL;

-- 3) Operations: add norm_required and enforce norm checks when required.
ALTER TABLE operations
    ADD COLUMN IF NOT EXISTS norm_required boolean NOT NULL DEFAULT true;

ALTER TABLE operations
    DROP CONSTRAINT IF EXISTS chk_operations_norm_required_valid;

ALTER TABLE operations
    ADD CONSTRAINT chk_operations_norm_required_valid
        CHECK (
            norm_required = false
            OR (
                min_norm IS NOT NULL
                AND max_norm IS NOT NULL
                AND min_norm > 0
                AND max_norm > 0
                AND min_norm <= max_norm
            )
        );

-- 4) Work shifts: notes -> note + uniqueness.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'work_shifts' AND column_name = 'notes'
    ) THEN
        ALTER TABLE work_shifts RENAME COLUMN notes TO note;
    END IF;
END $$;

ALTER TABLE work_shifts
    DROP CONSTRAINT IF EXISTS uq_work_shifts_employee_shift_work_date;

ALTER TABLE work_shifts
    ADD CONSTRAINT uq_work_shifts_employee_shift_work_date
        UNIQUE (employee_id, shift_id, work_date);

-- 5) App settings (effective max efficiency lookup).
CREATE TABLE IF NOT EXISTS app_settings (
    id bigserial PRIMARY KEY,
    max_efficiency_percent numeric(10,2) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NULL
);

CREATE INDEX IF NOT EXISTS idx_app_settings_valid_window
    ON app_settings (valid_from, valid_until);

-- 6) Work-code categories paid flag (for absence/sick leave split).
ALTER TABLE work_code_categories
    ADD COLUMN IF NOT EXISTS is_paid boolean;

-- 7) Daily report categories reshape.
ALTER TABLE daily_report_categories
    ADD COLUMN IF NOT EXISTS total_paid_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS performance_coefficient numeric(12,6) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS approved_performance_coefficient numeric(12,6) NOT NULL DEFAULT 0;

ALTER TABLE daily_report_categories
    DROP COLUMN IF EXISTS total_compensated_minutes,
    DROP COLUMN IF EXISTS total_approved_minutes,
    DROP COLUMN IF EXISTS performance_rate,
    DROP COLUMN IF EXISTS approved_performance_rate,
    DROP COLUMN IF EXISTS category_coefficient_snapshot;

ALTER TABLE daily_report_categories
    DROP CONSTRAINT IF EXISTS uq_daily_report_category_report_category;

ALTER TABLE daily_report_categories
    ADD CONSTRAINT uq_daily_report_category_report_category
        UNIQUE (daily_report_id, work_code_category_id);

-- 8) Daily reports reshape.
ALTER TABLE daily_reports
    ADD COLUMN IF NOT EXISTS total_absence_paid_minutes integer,
    ADD COLUMN IF NOT EXISTS total_absence_unpaid_minutes integer,
    ADD COLUMN IF NOT EXISTS total_sick_leave_paid_minutes integer,
    ADD COLUMN IF NOT EXISTS total_sick_leave_unpaid_minutes integer,
    ADD COLUMN IF NOT EXISTS approved_performance_coefficient numeric(12,6),
    ADD COLUMN IF NOT EXISTS is_meal_allowed boolean NOT NULL DEFAULT false;

ALTER TABLE daily_reports
    DROP COLUMN IF EXISTS total_absence_minutes,
    DROP COLUMN IF EXISTS total_paid_absence_minutes,
    DROP COLUMN IF EXISTS total_unpaid_absence_minutes,
    DROP COLUMN IF EXISTS meal_allowance_num;

ALTER TABLE daily_reports
    DROP CONSTRAINT IF EXISTS uq_daily_reports_employee_shift;

ALTER TABLE daily_reports
    ADD CONSTRAINT uq_daily_reports_employee_shift
        UNIQUE (employee_id, work_shift_id);

-- 9) Monthly report categories reshape.
ALTER TABLE monthly_report_categories
    ADD COLUMN IF NOT EXISTS total_weighted_norm_minutes numeric(14,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_approved_minutes numeric(14,4);

ALTER TABLE monthly_report_categories
    DROP COLUMN IF EXISTS performance_coefficient,
    DROP COLUMN IF EXISTS weighted_norm_minutes,
    DROP COLUMN IF EXISTS effective_hours;

ALTER TABLE monthly_report_categories
    DROP CONSTRAINT IF EXISTS uq_monthly_report_category_report_category;

ALTER TABLE monthly_report_categories
    ADD CONSTRAINT uq_monthly_report_category_report_category
        UNIQUE (monthly_report_id, work_code_category_id);

-- 10) Monthly reports period key + totals reshape.
ALTER TABLE monthly_reports
    ADD COLUMN IF NOT EXISTS start_date date,
    ADD COLUMN IF NOT EXISTS end_date date,
    ADD COLUMN IF NOT EXISTS total_absence_paid_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_absence_unpaid_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_sick_leave_paid_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_sick_leave_unpaid_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_weighted_norm_minutes numeric(14,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS approved_performance_coefficient numeric(12,6);

-- Backfill date range from report_year/report_month if present.
UPDATE monthly_reports
SET start_date = make_date(report_year, report_month, 1),
    end_date = (make_date(report_year, report_month, 1)
                + INTERVAL '1 month - 1 day')::date
WHERE start_date IS NULL
  AND report_year IS NOT NULL
  AND report_month IS NOT NULL;

ALTER TABLE monthly_reports
    ALTER COLUMN start_date SET NOT NULL,
    ALTER COLUMN end_date SET NOT NULL;

ALTER TABLE monthly_reports
    DROP COLUMN IF EXISTS report_year,
    DROP COLUMN IF EXISTS report_month,
    DROP COLUMN IF EXISTS total_absence_minutes,
    DROP COLUMN IF EXISTS total_paid_absence_minutes,
    DROP COLUMN IF EXISTS total_unpaid_absence_minutes,
    DROP COLUMN IF EXISTS total_compensated_minutes,
    DROP COLUMN IF EXISTS total_effective_minutes,
    DROP COLUMN IF EXISTS meal_allowance_amount,
    DROP COLUMN IF EXISTS total_meal_allowance;

ALTER TABLE monthly_reports
    DROP CONSTRAINT IF EXISTS uq_monthly_reports_employee_period;

ALTER TABLE monthly_reports
    ADD CONSTRAINT uq_monthly_reports_employee_period
        UNIQUE (employee_id, start_date, end_date);

CREATE INDEX IF NOT EXISTS idx_monthly_reports_employee_period
    ON monthly_reports (employee_id, start_date, end_date);

COMMIT;

