-- =============================================================================
-- An efficiency somebody typed over the measured one
-- =============================================================================
-- WHAT CHANGES
--   work_logs.performance_rate_manual (+ _by, _at) — the efficiency percentage a
--     supervisor typed over the one the program measured, and who typed it when.
--     NULL on every existing row and on every row nobody touches.
--
-- WHY A COLUMN AND NOT performance_rate / approved_performance_rate
--   performance_rate is the MEASURED figure (100 × kom/h ÷ minNorm) and
--   approved_performance_rate is that same figure capped at max_efficiency_percent.
--   Both are derived, and the recalc engine (WorkLogPerformanceCalculator, via
--   DailyRecalcService.computeWeightedRates) recomputes the paid rate on every
--   recalculation — which is exactly right for a derived value and exactly wrong
--   for one a person entered by hand, which a recalculation would erase.
--
--   So they stay apart: performance_rate stays the measured value the row shows
--   as "obračunato", and performance_rate_manual is the value somebody decided
--   instead. The EFFECTIVE PAID rate is `manual ?? approved`, resolved in one
--   place (WorkLogPerformanceCalculator.calculateApprovedPerformanceRate). That
--   keeps the original visible — "80 %, ručno, obračunato 70 %" is a fact the row
--   can state, not something to reconstruct.
--
-- HOW IT REACHES THE DAILY AND MONTHLY UNIQUE
--   Nothing here computes anything. The manual rate becomes the log's PAID rate at
--   that one seam, so computeWeightedRates folds it into the category's
--   approved_performance_coefficient, total_weighted_norm_minutes becomes
--   minutes × (manual ÷ 100) for that log's share, and the daily report (one per
--   shift) and the monthly report (one per employee-month) roll it up with no
--   further change. The coefficient the user means — "novi učinak ÷ 100" — is
--   precisely approved_performance_coefficient.
--
--   Unlike the per-operation COEFFICIENT (V22), the efficiency is already
--   aggregated minute-weighted across a category's logs, so no report row has to
--   split and no unique key changes. This migration touches work_logs only.
--
-- WHAT THE MANUAL VALUE IGNORES
--   It is used exactly as typed: it is NOT capped at max_efficiency_percent, and
--   it overrides full-performance crediting (probation / "šef sektora"). An
--   explicit decision by a supervisor wins over both — that is the owner's rule.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing moves. No work log gets a manual efficiency here; the columns exist
--   and are empty, so every recalculation reproduces today's figures until
--   somebody types a value.
--
-- WHY NO AUDIT CHANGES
--   work_logs is registered in audit_tables and its trigger has no column list,
--   so the three new columns are audited from the moment they exist.
-- =============================================================================

ALTER TABLE public.work_logs
    ADD COLUMN performance_rate_manual numeric(38,2),
    ADD COLUMN performance_rate_manual_by bigint,
    ADD COLUMN performance_rate_manual_at timestamp with time zone;

ALTER TABLE public.work_logs
    ADD CONSTRAINT fk_work_logs_performance_rate_manual_by
        FOREIGN KEY (performance_rate_manual_by) REFERENCES public.users(id);

-- A negative efficiency would pay negative time. Zero is allowed: crediting a log
-- at 0 % (no norm output recognised) is a decision a supervisor may make. There is
-- deliberately no upper bound — the manual value is used exactly as entered.
ALTER TABLE public.work_logs
    ADD CONSTRAINT chk_work_logs_performance_rate_manual_non_negative
        CHECK (performance_rate_manual IS NULL OR performance_rate_manual >= 0);

-- The three travel together. A value with nobody behind it cannot be explained
-- later, and an author without a value is a row recording that nothing happened.
ALTER TABLE public.work_logs
    ADD CONSTRAINT chk_work_logs_performance_rate_manual_complete
        CHECK (
            (performance_rate_manual IS NULL
                AND performance_rate_manual_by IS NULL
                AND performance_rate_manual_at IS NULL)
            OR
            (performance_rate_manual IS NOT NULL
                AND performance_rate_manual_by IS NOT NULL
                AND performance_rate_manual_at IS NOT NULL)
        );

-- Only the overridden ones, which are the exception rather than the rule.
CREATE INDEX idx_work_logs_performance_rate_manual
    ON public.work_logs (performance_rate_manual)
    WHERE performance_rate_manual IS NOT NULL;
