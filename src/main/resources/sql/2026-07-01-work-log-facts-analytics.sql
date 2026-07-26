-- Analytics fact table: one row per ACTIVE work_log, denormalized for fast GROUP BY
-- aggregation across the 5 analytics report pages (product/operation/employee/date/shift
-- dimensions). Synced by AnalyticsFactSyncService.upsertFactsForShift() during the daily
-- recalc write-phase (report_worker/DailyRecalcService.processJobWritePhase), immediately
-- after categoryRepo.flush(). Rows are DELETEd (not flagged) when the source work_log is
-- archived/deactivated, so every downstream aggregate query implicitly only sees active
-- data — no analytics page needs an "include archived" view.
--
-- approved_performance_rate here is computed via WorkLogPerformanceCalculator (a per-log,
-- deterministic formula), NOT copied from work_logs.approved_performance_rate — that source
-- column is never populated by the recalc engine and is unreliable for analytics.
--
-- NOT partitioned. Current volume is tiny (tens of work_logs); a plain table with the
-- composite indexes below comfortably serves hundreds of thousands to low millions of rows.
-- Re-evaluate partitioning (RANGE by work_date, monthly or yearly) once this table exceeds
-- ~5-10M rows OR analytics query p95 latency exceeds ~500ms with these indexes in place.
-- If/when partitioned, the PK must become (work_log_id, work_date) — plan the upsert-key
-- change together with that migration, not before.

CREATE TABLE IF NOT EXISTS work_log_facts (
    work_log_id                BIGINT PRIMARY KEY REFERENCES work_logs(id) ON DELETE CASCADE,

    -- dimension FKs, retained (not just denormalized names) so multi-select ID filters
    -- (= ANY(:ids)) work directly against this table.
    work_shift_id               BIGINT NOT NULL REFERENCES work_shifts(id),
    employee_id                  BIGINT NOT NULL REFERENCES employees(id),
    operation_id                  BIGINT NOT NULL REFERENCES operations(id),
    product_id                     BIGINT NOT NULL REFERENCES products(id),
    production_order_id            BIGINT NULL REFERENCES production_orders(id),
    shift_type_id                   BIGINT NOT NULL REFERENCES shifts(id),

    -- denormalized, query-time-only columns — avoid a join in every one of the 5 aggregate
    -- queries. Accepted staleness risk: renaming a product/operation does not retroactively
    -- relabel historical fact rows unless a future follow-up adds a sync-on-rename hook.
    work_date                        DATE NOT NULL,
    month_start                       DATE NOT NULL,
    shift_code                         TEXT NOT NULL,
    operation_start_time                TIME NOT NULL,
    product_name                         TEXT NOT NULL,
    operation_name                        TEXT NOT NULL,
    production_order_code                  TEXT NULL,
    note                                    TEXT NULL,

    -- raw measures; every aggregation (SUM/AVG/weighted-avg) happens in SQL at query time,
    -- never pre-aggregated here.
    duration_min                             INTEGER NOT NULL,
    quantity                                  INTEGER NOT NULL DEFAULT 0,
    scrap                                      INTEGER NOT NULL DEFAULT 0,
    approved_performance_rate                  NUMERIC(38,2) NULL,

    synced_at                                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Page 3: employee efficiency
CREATE INDEX IF NOT EXISTS idx_wlf_employee_work_date ON work_log_facts (employee_id, work_date);
-- Page 5: operation efficiency
CREATE INDEX IF NOT EXISTS idx_wlf_operation_work_date ON work_log_facts (operation_id, work_date);
-- Page 1, 4: product -> operation grouping
CREATE INDEX IF NOT EXISTS idx_wlf_product_operation_work_date ON work_log_facts (product_id, operation_id, work_date);
-- Page 2: date -> shift -> product -> operation -> employee grouping (finest grain query)
CREATE INDEX IF NOT EXISTS idx_wlf_date_shift_product_operation_employee
    ON work_log_facts (work_date, shift_type_id, product_id, operation_id, employee_id);
-- plain date range filter, used as a fallback/standalone predicate on several pages
CREATE INDEX IF NOT EXISTS idx_wlf_work_date ON work_log_facts (work_date);
-- shared filters across all 5 pages
CREATE INDEX IF NOT EXISTS idx_wlf_production_order ON work_log_facts (production_order_id);
CREATE INDEX IF NOT EXISTS idx_wlf_month_start ON work_log_facts (month_start);
CREATE INDEX IF NOT EXISTS idx_wlf_operation_start_time ON work_log_facts (operation_start_time);

-- note is free text, filtered via ILIKE '%...%' (noteLike) which cannot use a plain btree.
-- pg_trgm + GIN is the one access pattern in this feature that genuinely degrades without
-- an index at scale; unlike partitioning this is a safe, well-understood, non-schema-breaking
-- addition, so it is added now rather than deferred.
CREATE INDEX IF NOT EXISTS idx_wlf_note_trgm ON work_log_facts USING GIN (note gin_trgm_ops);
