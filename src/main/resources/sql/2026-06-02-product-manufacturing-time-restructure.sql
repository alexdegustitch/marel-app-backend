-- Restructure product_manufacturing_times:
--   1. Remove operation/norm fields and add product_id + product_name
--   2. Create product_manufacturing_time_operations table with the removed fields
--   3. Adjust triggers accordingly
BEGIN;

-- ──────────────────────────────────────────────────────────────
-- 1. Drop columns from product_manufacturing_times
-- ──────────────────────────────────────────────────────────────

ALTER TABLE product_manufacturing_times
    DROP COLUMN IF EXISTS operation_id,
    DROP COLUMN IF EXISTS operation_name,
    DROP COLUMN IF EXISTS manufacturing_date,
    DROP COLUMN IF EXISTS units_per_product_snapshot,
    DROP COLUMN IF EXISTS units_per_product_overridden,
    DROP COLUMN IF EXISTS units_per_product_value,
    DROP COLUMN IF EXISTS norm_snapshot,
    DROP COLUMN IF EXISTS norm_overridden,
    DROP COLUMN IF EXISTS norm_value,
    DROP COLUMN IF EXISTS norm_date_snapshot,
    DROP COLUMN IF EXISTS norm_date_overridden,
    DROP COLUMN IF EXISTS norm_date_value,
    DROP COLUMN IF EXISTS excluded;

-- ──────────────────────────────────────────────────────────────
-- 2. Add product_id and product_name to product_manufacturing_times
-- ──────────────────────────────────────────────────────────────

ALTER TABLE product_manufacturing_times
    ADD COLUMN IF NOT EXISTS product_id   bigint       NOT NULL REFERENCES products(id),
    ADD COLUMN IF NOT EXISTS product_name varchar(255) NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pmt_product_id
    ON product_manufacturing_times (product_id);

-- ──────────────────────────────────────────────────────────────
-- 3. Drop the resolve-values trigger — no longer needed on parent table
-- ──────────────────────────────────────────────────────────────

DROP TRIGGER IF EXISTS before_insert_update_pmt_resolve_values ON product_manufacturing_times;
DROP FUNCTION IF EXISTS trg_pmt_resolve_values();

-- ──────────────────────────────────────────────────────────────
-- 4. Create product_manufacturing_time_operations table
-- ──────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS product_manufacturing_time_operations (
    id                              bigserial PRIMARY KEY,

    product_manufacturing_time_id   bigint NOT NULL
        REFERENCES product_manufacturing_times(id) ON DELETE CASCADE,

    operation_id                    bigint       NOT NULL REFERENCES operations(id),
    operation_name                  varchar(255) NOT NULL,
    manufacturing_date              date         NOT NULL,

    units_per_product_snapshot      integer,
    units_per_product_overridden    boolean NOT NULL DEFAULT false,
    units_per_product_value         integer,

    norm_snapshot                   numeric(10,4),
    norm_overridden                 boolean NOT NULL DEFAULT false,
    norm_value                      numeric(10,4),

    norm_date_snapshot              date,
    norm_date_overridden            boolean NOT NULL DEFAULT false,
    norm_date_value                 date,

    excluded                        boolean NOT NULL DEFAULT false,

    created_at                      timestamptz NOT NULL DEFAULT NOW(),
    updated_at                      timestamptz NOT NULL DEFAULT NOW(),
    archived_at                     timestamptz,
    is_active                       boolean NOT NULL DEFAULT true
);

-- ──────────────────────────────────────────────────────────────
-- 5. Constraints on product_manufacturing_time_operations
-- ──────────────────────────────────────────────────────────────

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmto_units_per_product_value'
    ) THEN
        ALTER TABLE product_manufacturing_time_operations
            ADD CONSTRAINT chk_pmto_units_per_product_value
                CHECK (units_per_product_value IS NULL OR units_per_product_value > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pmto_norm_value'
    ) THEN
        ALTER TABLE product_manufacturing_time_operations
            ADD CONSTRAINT chk_pmto_norm_value
                CHECK (norm_value IS NULL OR norm_value >= 0);
    END IF;
END $$;

-- ──────────────────────────────────────────────────────────────
-- 6. Indexes on product_manufacturing_time_operations
-- ──────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_pmto_pmt_id
    ON product_manufacturing_time_operations (product_manufacturing_time_id);

CREATE INDEX IF NOT EXISTS idx_pmto_operation_id
    ON product_manufacturing_time_operations (operation_id);

CREATE INDEX IF NOT EXISTS idx_pmto_manufacturing_date
    ON product_manufacturing_time_operations (manufacturing_date DESC);

CREATE INDEX IF NOT EXISTS idx_pmto_active
    ON product_manufacturing_time_operations (is_active, manufacturing_date DESC)
    WHERE is_active = true;

-- ──────────────────────────────────────────────────────────────
-- 7. Trigger: auto-update updated_at on product_manufacturing_time_operations
-- ──────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trg_pmto_touch_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS before_update_pmto_set_updated_at ON product_manufacturing_time_operations;
CREATE TRIGGER before_update_pmto_set_updated_at
BEFORE UPDATE ON product_manufacturing_time_operations
FOR EACH ROW
EXECUTE FUNCTION trg_pmto_touch_updated_at();

-- ──────────────────────────────────────────────────────────────
-- 8. Trigger: resolve _value fields from _snapshot / _overridden
--    on product_manufacturing_time_operations
-- ──────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trg_pmto_resolve_values()
RETURNS trigger AS $$
BEGIN
    IF NOT NEW.units_per_product_overridden THEN
        NEW.units_per_product_value := NEW.units_per_product_snapshot;
    END IF;

    IF NOT NEW.norm_overridden THEN
        NEW.norm_value := NEW.norm_snapshot;
    END IF;

    IF NOT NEW.norm_date_overridden THEN
        NEW.norm_date_value := NEW.norm_date_snapshot;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS before_insert_update_pmto_resolve_values ON product_manufacturing_time_operations;
CREATE TRIGGER before_insert_update_pmto_resolve_values
BEFORE INSERT OR UPDATE ON product_manufacturing_time_operations
FOR EACH ROW
EXECUTE FUNCTION trg_pmto_resolve_values();

COMMIT;

