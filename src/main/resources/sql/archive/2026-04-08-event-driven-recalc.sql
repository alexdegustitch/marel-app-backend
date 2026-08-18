-- Migration: Add version column to recalculation queue tables for event-driven processing
-- Purpose: Enable version checking to prevent stale recalculation writes under concurrent updates

BEGIN;

-- Add version column to daily_report_recalc_queue
ALTER TABLE daily_report_recalc_queue
    ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0;

-- Add version column to monthly_report_recalc_queue
ALTER TABLE monthly_report_recalc_queue
    ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0;

COMMIT;

