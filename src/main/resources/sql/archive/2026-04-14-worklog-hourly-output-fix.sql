BEGIN;

-- Recreate generated column with a stable denominator based on total seconds.
ALTER TABLE work_logs
    DROP COLUMN IF EXISTS hourly_output;

ALTER TABLE work_logs
    ADD COLUMN hourly_output numeric GENERATED ALWAYS AS (
        CASE
            WHEN quantity IS NULL THEN NULL
            WHEN EXTRACT(EPOCH FROM (end_at - start_at)) <= 0 THEN NULL
            ELSE ROUND((quantity::numeric * 3600) / EXTRACT(EPOCH FROM (end_at - start_at)), 2)
        END
    ) STORED;

COMMIT;

