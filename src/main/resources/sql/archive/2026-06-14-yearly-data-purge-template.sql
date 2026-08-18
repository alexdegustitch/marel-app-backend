-- Yearly data purge template for Marel backend
--
-- IMPORTANT:
-- - Run during a maintenance window while the app/workers are stopped.
-- - Change the year in the purge_scope CTE before executing.
-- - This permanently deletes data for the selected year from:
--   work_logs, work_shifts,
--   daily_report_categories, daily_reports, daily_report_recalc_queue,
--   monthly_report_categories, monthly_reports, monthly_report_recalc_queue,
--   payroll_adjustments, payroll_run_item_categories,
--   employee_payroll_run_item_updates, employee_record_updates, employee_records,
--   payroll_run_items, payroll_runs.

BEGIN;

DROP TABLE IF EXISTS purge_scope;
DROP TABLE IF EXISTS purge_work_shift_ids;
DROP TABLE IF EXISTS purge_daily_report_ids;
DROP TABLE IF EXISTS purge_monthly_report_ids;
DROP TABLE IF EXISTS purge_payroll_run_ids;
DROP TABLE IF EXISTS purge_payroll_run_item_ids;
DROP TABLE IF EXISTS purge_employee_record_ids;

CREATE TEMP TABLE purge_scope ON COMMIT DROP AS
SELECT
    2025::int AS target_year,
    make_date(2025, 1, 1) AS start_date,
    make_date(2026, 1, 1) AS next_year_date;

CREATE TEMP TABLE purge_work_shift_ids ON COMMIT DROP AS
SELECT ws.id
FROM work_shifts ws
JOIN purge_scope s
  ON ws.work_date >= s.start_date
 AND ws.work_date < s.next_year_date;

CREATE TEMP TABLE purge_daily_report_ids ON COMMIT DROP AS
SELECT dr.id
FROM daily_reports dr
JOIN purge_work_shift_ids pws ON pws.id = dr.work_shift_id;

CREATE TEMP TABLE purge_monthly_report_ids ON COMMIT DROP AS
SELECT mr.id
FROM monthly_reports mr
JOIN purge_scope s
  ON mr.start_date >= s.start_date
 AND mr.start_date < s.next_year_date;

CREATE TEMP TABLE purge_employee_record_ids ON COMMIT DROP AS
SELECT er.id
FROM employee_records er
JOIN purge_scope s
  ON er.start_date >= s.start_date
 AND er.start_date < s.next_year_date;

CREATE TEMP TABLE purge_payroll_run_ids ON COMMIT DROP AS
SELECT pr.id
FROM payroll_runs pr
JOIN purge_scope s ON pr.report_year = s.target_year;

CREATE TEMP TABLE purge_payroll_run_item_ids ON COMMIT DROP AS
SELECT DISTINCT pri.id
FROM payroll_run_items pri
LEFT JOIN purge_payroll_run_ids ppr ON ppr.id = pri.payroll_run_id
LEFT JOIN purge_monthly_report_ids pmr ON pmr.id = pri.monthly_report_id
WHERE ppr.id IS NOT NULL OR pmr.id IS NOT NULL;

DELETE FROM employee_payroll_run_item_updates
WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids);

DELETE FROM payroll_adjustments
WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids);

DELETE FROM payroll_run_item_categories
WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids);

DELETE FROM payroll_run_items
WHERE id IN (SELECT id FROM purge_payroll_run_item_ids);

DELETE FROM payroll_runs
WHERE id IN (SELECT id FROM purge_payroll_run_ids);

DELETE FROM monthly_report_categories
WHERE monthly_report_id IN (SELECT id FROM purge_monthly_report_ids);

DELETE FROM monthly_report_recalc_queue
WHERE report_year = (SELECT target_year FROM purge_scope);

DELETE FROM monthly_reports
WHERE id IN (SELECT id FROM purge_monthly_report_ids);

DELETE FROM daily_report_categories
WHERE daily_report_id IN (SELECT id FROM purge_daily_report_ids);

DELETE FROM daily_report_recalc_queue
WHERE work_shift_id IN (SELECT id FROM purge_work_shift_ids);

DELETE FROM daily_reports
WHERE id IN (SELECT id FROM purge_daily_report_ids);

DELETE FROM work_logs
WHERE work_shift_id IN (SELECT id FROM purge_work_shift_ids);

DELETE FROM work_shifts
WHERE id IN (SELECT id FROM purge_work_shift_ids);

DELETE FROM employee_record_updates
WHERE employee_record_id IN (SELECT id FROM purge_employee_record_ids);

DELETE FROM employee_records
WHERE id IN (SELECT id FROM purge_employee_record_ids);

COMMIT;

