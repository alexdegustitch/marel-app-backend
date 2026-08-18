BEGIN;

-- Add period column used as the payroll month key (first day of month from monthly_report.start_date)
ALTER TABLE payroll_run_items
    ADD COLUMN IF NOT EXISTS period date;

-- total_net_amount was renamed to total_net_earnings
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'payroll_run_items'
          AND column_name = 'total_net_amount'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'payroll_run_items'
          AND column_name = 'total_net_earnings'
    ) THEN
        EXECUTE 'ALTER TABLE payroll_run_items RENAME COLUMN total_net_amount TO total_net_earnings';
    END IF;
END $$;

ALTER TABLE payroll_run_items
    ADD COLUMN IF NOT EXISTS meal_allowance_amount_system numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS meal_allowance_amount_overridden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS transport_allowance_amount_system numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS transport_allowance_amount_overridden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS hourly_rate_system numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS hourly_rate_overridden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS base_bonus_amount_system numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS base_bonus_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS base_bonus_amount_overridden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS bonus_correction_amount_system numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bonus_correction_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bonus_correction_amount_overridden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS total_bonus_amount_system numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_bonus_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_bonus_amount_overridden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS work_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS net_adjusted_work_hours numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS previously_paid_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS previous_balance_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_balance_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS net_payable_amount numeric(38,2) NOT NULL DEFAULT 0;

-- Backfill period from linked monthly report where possible
UPDATE payroll_run_items pri
SET period = mr.start_date
FROM monthly_reports mr
WHERE pri.monthly_report_id = mr.id
  AND pri.period IS NULL;

-- Backfill new bonus snapshot values from the old bonus_amount if it exists
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'payroll_run_items'
          AND column_name = 'bonus_amount'
    ) THEN
        EXECUTE '
            UPDATE payroll_run_items
            SET base_bonus_amount = COALESCE(base_bonus_amount, 0),
                bonus_correction_amount = COALESCE(bonus_correction_amount, 0),
                total_bonus_amount = COALESCE(NULLIF(total_bonus_amount, 0), COALESCE(bonus_amount, 0)),
                total_bonus_amount_system = COALESCE(NULLIF(total_bonus_amount_system, 0), COALESCE(bonus_amount, 0))
        ';

        EXECUTE 'ALTER TABLE payroll_run_items DROP COLUMN bonus_amount';
    END IF;
END $$;

-- Ensure total_net_earnings is populated for old rows
UPDATE payroll_run_items
SET total_net_earnings = COALESCE(total_net_earnings, total_gross_amount, 0)
WHERE total_net_earnings IS NULL;

COMMIT;

