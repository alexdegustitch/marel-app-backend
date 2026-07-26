-- Renames production_order_deadlines.order_deadline -> deadline_order and
-- deadline_date -> deadline_date_to, and adds an optional deadline_date_from
-- so a deadline can be expressed as a single date or a date range.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'production_order_deadlines' AND column_name = 'order_deadline'
    ) THEN
        ALTER TABLE production_order_deadlines RENAME COLUMN order_deadline TO deadline_order;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'production_order_deadlines' AND column_name = 'deadline_date'
    ) THEN
        ALTER TABLE production_order_deadlines RENAME COLUMN deadline_date TO deadline_date_to;
    END IF;
END $$;

ALTER TABLE production_order_deadlines
    ADD COLUMN IF NOT EXISTS deadline_date_from DATE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_production_order_deadlines_date_range'
    ) THEN
        ALTER TABLE production_order_deadlines
            ADD CONSTRAINT chk_production_order_deadlines_date_range
            CHECK (deadline_date_from IS NULL OR deadline_date_from <= deadline_date_to);
    END IF;
END $$;
