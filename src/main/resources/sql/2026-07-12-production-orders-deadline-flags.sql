-- production_orders.delivery_deadline becomes a free-text override (can hold a
-- literal description instead of a date), and gains three status flags that
-- take precedence over any dated deadline when set.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'production_orders' AND column_name = 'delivery_deadline' AND data_type = 'date'
    ) THEN
        ALTER TABLE production_orders ALTER COLUMN delivery_deadline TYPE TEXT USING delivery_deadline::text;
    END IF;
END $$;

ALTER TABLE production_orders
    ADD COLUMN IF NOT EXISTS is_high_priority BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_announced BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS has_successive_deliveries BOOLEAN DEFAULT false;

-- Optional piece count attached to a dated deadline (e.g. "21. februar 2022 - 160 kom.")
ALTER TABLE production_order_deadlines
    ADD COLUMN IF NOT EXISTS quantity INTEGER;

-- Optional per-batch deadline attached to a specific quantity revision of a line item
ALTER TABLE production_order_line_item_quantities
    ADD COLUMN IF NOT EXISTS delivery_deadline DATE;
