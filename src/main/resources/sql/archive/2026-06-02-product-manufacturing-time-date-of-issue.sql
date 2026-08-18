-- Move manufacturing_date out of product_manufacturing_time_operations
-- and add date_of_issue to product_manufacturing_times
BEGIN;

-- 1. Drop manufacturing_date from product_manufacturing_time_operations
ALTER TABLE product_manufacturing_time_operations
    DROP COLUMN IF EXISTS manufacturing_date;

-- 2. Add date_of_issue to product_manufacturing_times
ALTER TABLE product_manufacturing_times
    ADD COLUMN IF NOT EXISTS date_of_issue date NOT NULL DEFAULT CURRENT_DATE;

-- Remove the default after backfill so future inserts must supply the value explicitly
ALTER TABLE product_manufacturing_times
    ALTER COLUMN date_of_issue DROP DEFAULT;

CREATE INDEX IF NOT EXISTS idx_pmt_date_of_issue
    ON product_manufacturing_times (date_of_issue DESC);

COMMIT;

