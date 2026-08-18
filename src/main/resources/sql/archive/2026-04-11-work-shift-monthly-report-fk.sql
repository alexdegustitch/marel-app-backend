-- Add work_code_category_id to work_shifts (nullable FK)
ALTER TABLE work_shifts
    ADD COLUMN IF NOT EXISTS work_code_category_id bigint
        REFERENCES work_code_categories(id);

CREATE INDEX IF NOT EXISTS idx_work_shifts_work_code_category_id
    ON work_shifts (work_code_category_id);

-- Add employee_record_id to monthly_reports (required FK)
-- Step 1: add as nullable first so we can backfill
ALTER TABLE monthly_reports
    ADD COLUMN IF NOT EXISTS employee_record_id bigint
        REFERENCES employee_records(id);

-- Step 2: backfill from employee_records by matching employee + month window
UPDATE monthly_reports mr
SET employee_record_id = er.id
FROM employee_records er
WHERE mr.employee_record_id IS NULL
  AND er.employee_id = mr.employee_id
  AND er.start_date = mr.start_date;

-- Step 3: enforce NOT NULL (fails if any row is still unmatched)
DO $$
DECLARE
    missing_count bigint;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM monthly_reports
    WHERE employee_record_id IS NULL;

    IF missing_count > 0 THEN
        RAISE EXCEPTION 'Cannot set monthly_reports.employee_record_id NOT NULL — % rows still unmatched', missing_count;
    END IF;
END $$;

ALTER TABLE monthly_reports
    ALTER COLUMN employee_record_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_monthly_reports_employee_record_id
    ON monthly_reports (employee_record_id);

