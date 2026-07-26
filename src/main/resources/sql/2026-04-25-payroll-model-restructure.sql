-- =============================================================================
-- 2026-04-25 · Payroll model restructure
--
-- Changes:
--   1. payroll_adjustment_categories — complete column restructure
--   2. payroll_adjustments           — add system/quantity/unit fields,
--                                      rename reason→note, add edited_by/edited_at
--   3. payroll_run_items             — remove meal/transport/work-amount fields,
--                                      rename total_gross_amount→total_gross_earnings,
--                                      add manual_adjusted_hours, total_payroll_hours,
--                                      total_deductions_amount
-- =============================================================================

-- ─── 1. payroll_adjustment_categories ──────────────────────────────────────

-- Drop old columns
ALTER TABLE payroll_adjustment_categories
    DROP COLUMN IF EXISTS category_code,
    DROP COLUMN IF EXISTS category_name,
    DROP COLUMN IF EXISTS category_type,
    DROP COLUMN IF EXISTS amount_type,
    DROP COLUMN IF EXISTS default_value,
    DROP COLUMN IF EXISTS affects_gross,
    DROP COLUMN IF EXISTS affects_net,
    DROP COLUMN IF EXISTS valid_from,
    DROP COLUMN IF EXISTS valid_until;

-- Add new columns (if not already present)
ALTER TABLE payroll_adjustment_categories
    ADD COLUMN IF NOT EXISTS code              VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS name              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS section_code      VARCHAR(50),
    ADD COLUMN IF NOT EXISTS section_order     INTEGER,
    ADD COLUMN IF NOT EXISTS sort_order        INTEGER,
    ADD COLUMN IF NOT EXISTS impact_code       VARCHAR(50),
    ADD COLUMN IF NOT EXISTS input_type        VARCHAR(50),
    ADD COLUMN IF NOT EXISTS is_manual         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS allow_override    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS override_target   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS allow_negative    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS visible_in_ui     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS visible_in_pdf    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS calculation_key   VARCHAR(100);

-- is_active already existed; ensure it has the right default
ALTER TABLE payroll_adjustment_categories
    ALTER COLUMN is_active SET DEFAULT TRUE;

-- Unique constraint on code
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_payroll_adjustment_categories_code'
    ) THEN
        ALTER TABLE payroll_adjustment_categories
            ADD CONSTRAINT uq_payroll_adjustment_categories_code UNIQUE (code);
    END IF;
END $$;

-- Remove empty-string default used during column creation
ALTER TABLE payroll_adjustment_categories
    ALTER COLUMN code DROP DEFAULT;


-- ─── 2. payroll_adjustments ────────────────────────────────────────────────

-- Add system/quantity/unit fields
ALTER TABLE payroll_adjustments
    ADD COLUMN IF NOT EXISTS system_quantity    NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS quantity           NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS system_unit_amount NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS unit_amount        NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS system_amount      NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS is_overridden      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS edited_by          BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS edited_at          TIMESTAMPTZ;

-- Rename reason → note (safe: keep data)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'payroll_adjustments' AND column_name = 'reason') THEN
        ALTER TABLE payroll_adjustments RENAME COLUMN reason TO note;
    END IF;
END $$;

-- Ensure unique constraint (payroll_run_item_id, payroll_adjustment_category_id)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_payroll_adjustments_item_category'
    ) THEN
        ALTER TABLE payroll_adjustments
            ADD CONSTRAINT uq_payroll_adjustments_item_category
                UNIQUE (payroll_run_item_id, payroll_adjustment_category_id);
    END IF;
END $$;


-- ─── 3. payroll_run_items ──────────────────────────────────────────────────

-- Remove meal allowance columns
ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS meal_allowance_num,
    DROP COLUMN IF EXISTS meal_allowance_amount,
    DROP COLUMN IF EXISTS meal_allowance_amount_system,
    DROP COLUMN IF EXISTS meal_allowance_amount_overridden,
    DROP COLUMN IF EXISTS total_meal_allowance,
    DROP COLUMN IF EXISTS remove_meal_allowance;

-- Remove transport allowance columns
ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS transport_allowance_num,
    DROP COLUMN IF EXISTS transport_allowance_amount,
    DROP COLUMN IF EXISTS transport_allowance_amount_system,
    DROP COLUMN IF EXISTS transport_allowance_amount_overridden,
    DROP COLUMN IF EXISTS total_transport_allowance,
    DROP COLUMN IF EXISTS remove_transport_allowance;

-- Remove legacy financial fields
ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS adjustment_amount,
    DROP COLUMN IF EXISTS work_amount,
    DROP COLUMN IF EXISTS net_adjusted_work_hours,
    DROP COLUMN IF EXISTS total_net_earnings;

-- Rename total_gross_amount → total_gross_earnings
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'payroll_run_items' AND column_name = 'total_gross_amount') THEN
        ALTER TABLE payroll_run_items RENAME COLUMN total_gross_amount TO total_gross_earnings;
    END IF;
END $$;

-- Add new columns
ALTER TABLE payroll_run_items
    ADD COLUMN IF NOT EXISTS manual_adjusted_hours  NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS total_payroll_hours    NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS total_gross_earnings   NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS total_deductions_amount NUMERIC(18, 2);

-- Seed sane defaults for existing rows
UPDATE payroll_run_items SET manual_adjusted_hours   = 0  WHERE manual_adjusted_hours IS NULL;
UPDATE payroll_run_items SET total_payroll_hours     = ROUND(total_work_minutes / 60.0, 4) WHERE total_payroll_hours IS NULL;
UPDATE payroll_run_items SET total_gross_earnings    = 0  WHERE total_gross_earnings IS NULL;
UPDATE payroll_run_items SET total_deductions_amount = 0  WHERE total_deductions_amount IS NULL;


-- ─── 4. Seed payroll_adjustment_categories catalog ────────────────────────

INSERT INTO payroll_adjustment_categories
    (code, name, section_code, section_order, sort_order, impact_code, input_type,
     is_manual, allow_override, override_target, allow_negative,
     is_active, visible_in_ui, visible_in_pdf, calculation_key,
     created_at)
VALUES
    ('MEAL_ALLOWANCE',             'Topli obrok',                              'ADDITIONS',   1, 10, 'GROSS_PLUS',      'QTY_X_RATE', FALSE, TRUE,  'UNIT_AMOUNT', FALSE, TRUE, TRUE, TRUE, 'MEAL_BY_ELIGIBLE_SHIFTS',        NOW()),
    ('TRANSPORT_ALLOWANCE',        'Prevoz',                                   'ADDITIONS',   1, 20, 'GROSS_PLUS',      'AMOUNT',     FALSE, TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, 'TRANSPORT_BY_WORK_DAYS',         NOW()),
    ('FIXED_SALARY',               'Fiksni L.D.',                              'ADDITIONS',   1, 30, 'GROSS_PLUS',      'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('MONTHLY_BONUS',              'Mesečni bonus',                            'ADDITIONS',   1, 40, 'GROSS_PLUS',      'AMOUNT',     FALSE, FALSE, 'COMPONENTS',  FALSE, TRUE, TRUE, TRUE, 'MONTHLY_BONUS_FROM_COMPONENTS',  NOW()),
    ('POSITIVE_NEGATIVE_CORRECTION','Pozitivna / negativna korekcija',         'ADDITIONS',   1, 50, 'GROSS_PLUS',      'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      TRUE,  TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('OTHER',                      'Ostalo',                                   'ADDITIONS',   1, 60, 'GROSS_PLUS',      'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      TRUE,  TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('INSTALLMENT',                'Rata',                                     'SETTLEMENTS', 2, 10, 'DEDUCTION_MINUS', 'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('PHONE_CURRENT_MONTH',        'Telefon za tekući mesec',                  'SETTLEMENTS', 2, 20, 'DEDUCTION_MINUS', 'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('PHONE_PREVIOUS_MONTH',       'Telefon za prethodni mesec',               'SETTLEMENTS', 2, 30, 'DEDUCTION_MINUS', 'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('PAID_PART_1',                'Isplaćeno prvi deo',                       'SETTLEMENTS', 2, 40, 'PAYMENT_MINUS',   'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('PAID_PART_2',                'Isplaćeno drugi deo',                      'SETTLEMENTS', 2, 50, 'PAYMENT_MINUS',   'AMOUNT',     TRUE,  TRUE,  'AMOUNT',      FALSE, TRUE, TRUE, TRUE, NULL,                            NOW()),
    ('PAID_PREVIOUS_PERIOD',       'Isplaćeno u prethodnom obračunskom periodu','SETTLEMENTS',2, 60, 'PAYMENT_MINUS',   'AMOUNT',     FALSE, FALSE, 'AMOUNT',      FALSE, TRUE, TRUE, TRUE, 'PAID_PREVIOUS_PERIOD',          NOW()),
    ('PREVIOUS_BALANCE',           'Prethodno stanje',                         'SETTLEMENTS', 2, 70, 'BALANCE_PLUS',    'AMOUNT',     FALSE, FALSE, 'AMOUNT',      TRUE,  TRUE, TRUE, TRUE, 'PREVIOUS_BALANCE',              NOW())
ON CONFLICT (code) DO NOTHING;

