-- Add needs_recalculation flag to payroll_run_items.
-- Set to true externally (e.g. when employee hourly_rate, transport_allowance,
-- bonus category, etc. changes) to trigger a full recalculation on next access,
-- independently of the monthly_report version check.

ALTER TABLE payroll_run_items
    ADD COLUMN needs_recalculation BOOLEAN NOT NULL DEFAULT FALSE;

