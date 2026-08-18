-- Product manufacturing times table
BEGIN;

CREATE TABLE IF NOT EXISTS product_manufacturing_times (
    id                              bigserial PRIMARY KEY,

    -- References
    user_id                         bigint NOT NULL REFERENCES users(id),
    operation_id                    bigint NOT NULL REFERENCES operations(id),

    -- Operation snapshot (denormalized at time of record)
    operation_name                  varchar(255) NOT NULL,

    -- Date of manufacture
    manufacturing_date              date NOT NULL,

    -- Units per product (snapshot / manual override / resolved value)
    units_per_product_snapshot      integer,
    units_per_product_overridden    boolean NOT NULL DEFAULT false,
    units_per_product_value         integer,

    -- Number of completed parts per hour (broj uradjenih delova za 1h)
    parts_per_hour_snapshot         numeric(10,4),
    parts_per_hour_overridden       boolean NOT NULL DEFAULT false,
    parts_per_hour_value            numeric(10,4),

    -- Norm date (snapshot / manual override / resolved value)
    norm_date_snapshot              date,
    norm_date_overridden            boolean NOT NULL DEFAULT false,
    norm_date_value                 date,

    -- Removed from list flag (izbacen sa liste)
    excluded                        boolean NOT NULL DEFAULT false,

    -- Product manufacturing coefficient (koeficijent izrade proizvoda)
    manufacturing_coefficient       numeric(10,6),

    -- Number of completed products per hour (broj uradjenih proizvoda za 1h)
    products_per_hour               numeric(10,4),

    -- Manufacturing time in mm:ss stored as total seconds
    -- Use EXTRACT(EPOCH FROM ...) or format with TO_CHAR for display
    manufacturing_time_seconds      integer,

    -- Audit timestamps
    created_at                      timestamptz NOT NULL DEFAULT NOW(),
    updated_at                      timestamptz NOT NULL DEFAULT NOW(),
    archived_at                     timestamptz,
    is_active                       boolean NOT NULL DEFAULT true
);

-- ──────────────────────────────────────────────────────────────
-- Constraints
-- ──────────────────────────────────────────────────────────────

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmt_units_per_product_value'
    ) THEN
        ALTER TABLE product_manufacturing_times
            ADD CONSTRAINT chk_pmt_units_per_product_value
                CHECK (units_per_product_value IS NULL OR units_per_product_value > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmt_parts_per_hour_value'
    ) THEN
        ALTER TABLE product_manufacturing_times
            ADD CONSTRAINT chk_pmt_parts_per_hour_value
                CHECK (parts_per_hour_value IS NULL OR parts_per_hour_value >= 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmt_manufacturing_time_seconds'
    ) THEN
        ALTER TABLE product_manufacturing_times
            ADD CONSTRAINT chk_pmt_manufacturing_time_seconds
                CHECK (manufacturing_time_seconds IS NULL OR manufacturing_time_seconds >= 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmt_manufacturing_coefficient'
    ) THEN
        ALTER TABLE product_manufacturing_times
            ADD CONSTRAINT chk_pmt_manufacturing_coefficient
                CHECK (manufacturing_coefficient IS NULL OR manufacturing_coefficient >= 0);
    END IF;
END $$;

-- ──────────────────────────────────────────────────────────────
-- Indexes
-- ──────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_pmt_operation_id
    ON product_manufacturing_times (operation_id);

CREATE INDEX IF NOT EXISTS idx_pmt_user_id
    ON product_manufacturing_times (user_id);

CREATE INDEX IF NOT EXISTS idx_pmt_manufacturing_date
    ON product_manufacturing_times (manufacturing_date DESC);

CREATE INDEX IF NOT EXISTS idx_pmt_operation_date
    ON product_manufacturing_times (operation_id, manufacturing_date DESC);

CREATE INDEX IF NOT EXISTS idx_pmt_active
    ON product_manufacturing_times (is_active, manufacturing_date DESC)
    WHERE is_active = true;

-- ──────────────────────────────────────────────────────────────
-- Trigger: auto-update updated_at on every UPDATE
-- ──────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trg_pmt_touch_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS before_update_pmt_set_updated_at ON product_manufacturing_times;
CREATE TRIGGER before_update_pmt_set_updated_at
BEFORE UPDATE ON product_manufacturing_times
FOR EACH ROW
EXECUTE FUNCTION trg_pmt_touch_updated_at();

-- ──────────────────────────────────────────────────────────────
-- Trigger: resolve _value fields from _system / _overridden
--   If overridden = true  -> value = the manually provided value (kept as-is)
--   If overridden = false -> value = system calculated value
-- ──────────────────────────────────────────────────────────────

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

DROP TRIGGER IF EXISTS before_insert_update_pmt_resolve_values ON product_manufacturing_times;
CREATE TRIGGER before_insert_update_pmt_resolve_values
BEFORE INSERT OR UPDATE ON product_manufacturing_times
FOR EACH ROW
EXECUTE FUNCTION trg_pmt_resolve_values();

COMMIT;




