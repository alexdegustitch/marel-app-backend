-- Rename parts_per_hour_* columns to norm_* on product_manufacturing_times
BEGIN;

ALTER TABLE product_manufacturing_times
    RENAME COLUMN parts_per_hour_snapshot  TO norm_snapshot;

ALTER TABLE product_manufacturing_times
    RENAME COLUMN parts_per_hour_overridden TO norm_overridden;

ALTER TABLE product_manufacturing_times
    RENAME COLUMN parts_per_hour_value      TO norm_value;

-- Update the resolve-values trigger to reference the renamed columns
CREATE OR REPLACE FUNCTION trg_pmt_resolve_values()
RETURNS trigger AS $$
BEGIN
    -- units_per_product_value
    IF NOT NEW.units_per_product_overridden THEN
        NEW.units_per_product_value := NEW.units_per_product_snapshot;
    END IF;

    -- norm_value
    IF NOT NEW.norm_overridden THEN
        NEW.norm_value := NEW.norm_snapshot;
    END IF;

    -- norm_date_value
    IF NOT NEW.norm_date_overridden THEN
        NEW.norm_date_value := NEW.norm_date_snapshot;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop and recreate the check constraint with the new column name
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmt_parts_per_hour_value'
    ) THEN
        ALTER TABLE product_manufacturing_times
            DROP CONSTRAINT chk_pmt_parts_per_hour_value;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmt_norm_value'
    ) THEN
        ALTER TABLE product_manufacturing_times
            ADD CONSTRAINT chk_pmt_norm_value
                CHECK (norm_value IS NULL OR norm_value >= 0);
    END IF;
END $$;

COMMIT;

