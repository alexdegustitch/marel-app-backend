-- Adds a status column to production_orders so orders can be tracked as
-- CREATED (kreiran) or DELIVERED (isporučen) on the production orders list page.
-- Existing rows default to CREATED (safe additive change, no data loss).

ALTER TABLE production_orders
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'CREATED';

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_production_orders_status'
    ) THEN
        ALTER TABLE production_orders
            ADD CONSTRAINT chk_production_orders_status CHECK (status IN ('CREATED', 'DELIVERED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_production_orders_status ON production_orders (status);
