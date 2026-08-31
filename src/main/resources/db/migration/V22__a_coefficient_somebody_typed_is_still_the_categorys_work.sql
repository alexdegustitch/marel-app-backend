-- =============================================================================
-- A coefficient somebody typed is still the category's work
-- =============================================================================
-- WHAT CHANGES
--   work_logs.norm_multiplier_manual (+ _by, _at) — the coefficient a supervisor
--     typed over the resolved one, and who typed it when. NULL on every existing
--     row and on every row nobody touches.
--   daily_report_categories.norm_multiplier — the coefficient the row was built
--     at, and part of its unique key — and norm_multiplier_default, the one it
--     WOULD have been built at.
--   monthly_report_categories.norm_multiplier (+ _default) — the same, one level up.
--   payroll_run_item_categories.category_default_coefficient_snapshot — so the
--     payslip can state the category's own coefficient while the rows behind it
--     keep theirs.
--
-- WHY A COLUMN AND NOT norm_multiplier_snapshot
--   The snapshot already exists and already means something precise: the
--   coefficient the compensation scheme RESOLVED for this log. The recalc engine
--   rewrites it whenever the resolution changes (WorkLogCompensationSnapshot.
--   apply/matches), which is exactly right for a derived value — and exactly
--   wrong for one a person entered by hand, which a recalculation would erase.
--
--   So the two stay apart: the snapshot is what the policy said, the manual
--   value is what somebody decided instead, and the EFFECTIVE coefficient is
--   `manual ?? snapshot`, resolved in one place (coefficientOf). That also keeps
--   the original visible — "1.20, izmenjeno, podrazumevano 1.10" is a fact the
--   row can state, not something to reconstruct.
--
-- WHY THE REPORT ROWS NEED A COEFFICIENT OF THEIR OWN
--   Until now a report row was one CATEGORY's work for a day, and the payroll
--   multiplied it by whatever that category's multiplier currently said. With a
--   per-operation override that is no longer a single number: four hours in
--   category J may be two hours at 1.10 and two at 1.20, and one row cannot hold
--   both. So the row becomes one category AT ONE COEFFICIENT, which is why the
--   coefficient joins the unique key.
--
--   The DEFAULT rides along beside it because two questions have two answers.
--   "What is this row worth" is the effective coefficient. "What would it have
--   been worth" is the default — which is what tells a screen it is looking at an
--   override at all ("1.20, izmenjeno, podrazumevano 1.10"), and what the payslip
--   prints when it folds a category's rows back into one line. Deriving it later
--   from the category would read whatever the category says THEN, not what this
--   row departed from.
--
--   Reading the coefficient off the row also ends a quieter problem: recalculating
--   an old month used the category's multiplier as it stands TODAY. It now uses
--   the one the work was recorded under, which is what every other snapshot in
--   this schema already does.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing moves. Every existing report row is backfilled with its own
--   category's current multiplier — precisely the number the payroll was already
--   multiplying it by — so recalculating any past day reproduces the same
--   figures. Locked payrolls are untouched either way: payroll_run_item_categories
--   already carries category_coefficient_snapshot and is not rebuilt.
--
--   No work log gets a manual coefficient here. The column exists and is empty.
--
-- WHY NO AUDIT CHANGES
--   work_logs is registered in audit_tables and its trigger has no column list,
--   so the three new columns are audited from the moment they exist. The report
--   category tables are derived data, are rebuilt by the recalc engine and were
--   never audited — that does not change.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The coefficient a person typed
-- -----------------------------------------------------------------------------

ALTER TABLE public.work_logs
    ADD COLUMN norm_multiplier_manual numeric(38,2),
    ADD COLUMN norm_multiplier_manual_by bigint,
    ADD COLUMN norm_multiplier_manual_at timestamp with time zone;

ALTER TABLE public.work_logs
    ADD CONSTRAINT fk_work_logs_norm_multiplier_manual_by
        FOREIGN KEY (norm_multiplier_manual_by) REFERENCES public.users(id);

-- A negative coefficient would pay negative time. Zero is allowed for the same
-- reason the category's own multiplier allows it: a category can be deliberately
-- outside the norm.
ALTER TABLE public.work_logs
    ADD CONSTRAINT chk_work_logs_norm_multiplier_manual_non_negative
        CHECK (norm_multiplier_manual IS NULL OR norm_multiplier_manual >= 0);

-- The three travel together. A value with nobody behind it cannot be explained
-- later, and an author without a value is a row recording that nothing happened.
ALTER TABLE public.work_logs
    ADD CONSTRAINT chk_work_logs_norm_multiplier_manual_complete
        CHECK (
            (norm_multiplier_manual IS NULL
                AND norm_multiplier_manual_by IS NULL
                AND norm_multiplier_manual_at IS NULL)
            OR
            (norm_multiplier_manual IS NOT NULL
                AND norm_multiplier_manual_by IS NOT NULL
                AND norm_multiplier_manual_at IS NOT NULL)
        );

-- Only the overridden ones, which are the exception rather than the rule.
CREATE INDEX idx_work_logs_norm_multiplier_manual
    ON public.work_logs (norm_multiplier_manual)
    WHERE norm_multiplier_manual IS NOT NULL;


-- -----------------------------------------------------------------------------
-- 2. The coefficient a daily report row was built at
-- -----------------------------------------------------------------------------

ALTER TABLE public.daily_report_categories
    ADD COLUMN norm_multiplier numeric(38,2) DEFAULT 1 NOT NULL,
    ADD COLUMN norm_multiplier_default numeric(38,2) DEFAULT 1 NOT NULL;

-- Backfill: what the payroll was already multiplying this row by. Nothing was
-- overridden before this migration, so the two are equal on every existing row.
UPDATE public.daily_report_categories drc
SET norm_multiplier = COALESCE(wcc.norm_multiplier, 1),
    norm_multiplier_default = COALESCE(wcc.norm_multiplier, 1)
FROM public.work_code_categories wcc
WHERE wcc.id = drc.work_code_category_id;

ALTER TABLE public.daily_report_categories
    ADD CONSTRAINT chk_drc_norm_multiplier_non_negative
        CHECK (norm_multiplier >= 0 AND norm_multiplier_default >= 0);

-- One row per category AT ONE COEFFICIENT.
ALTER TABLE public.daily_report_categories
    DROP CONSTRAINT uq_daily_report_category_report_category;

ALTER TABLE public.daily_report_categories
    ADD CONSTRAINT uq_daily_report_category_report_category
        UNIQUE (daily_report_id, work_code_category_id, norm_multiplier);


-- -----------------------------------------------------------------------------
-- 3. And the same one level up
-- -----------------------------------------------------------------------------

ALTER TABLE public.monthly_report_categories
    ADD COLUMN norm_multiplier numeric(38,2) DEFAULT 1 NOT NULL,
    ADD COLUMN norm_multiplier_default numeric(38,2) DEFAULT 1 NOT NULL;

UPDATE public.monthly_report_categories mrc
SET norm_multiplier = COALESCE(wcc.norm_multiplier, 1),
    norm_multiplier_default = COALESCE(wcc.norm_multiplier, 1)
FROM public.work_code_categories wcc
WHERE wcc.id = mrc.work_code_category_id;

ALTER TABLE public.monthly_report_categories
    ADD CONSTRAINT chk_mrc_norm_multiplier_non_negative
        CHECK (norm_multiplier >= 0 AND norm_multiplier_default >= 0);

ALTER TABLE public.monthly_report_categories
    DROP CONSTRAINT uq_monthly_report_category_report_category;

ALTER TABLE public.monthly_report_categories
    ADD CONSTRAINT uq_monthly_report_category_report_category
        UNIQUE (monthly_report_id, work_code_category_id, norm_multiplier);


-- -----------------------------------------------------------------------------
-- 4. What the payslip prints as the category's coefficient
-- -----------------------------------------------------------------------------
--
-- The payroll rows stay split — every category-and-coefficient pair is priced
-- and shown on screen. The PAYSLIP folds them back into one line per category,
-- at the category's own coefficient, so a worker is not asked to reconcile two
-- lines that say the same category twice. The hours on that line are scaled to
-- keep the money identical; this column is the coefficient it scales to.

ALTER TABLE public.payroll_run_item_categories
    ADD COLUMN category_default_coefficient_snapshot numeric(38,2);

UPDATE public.payroll_run_item_categories pric
SET category_default_coefficient_snapshot = COALESCE(wcc.norm_multiplier, 1)
FROM public.work_code_categories wcc
WHERE wcc.id = pric.work_code_category_id;

ALTER TABLE public.payroll_run_item_categories
    ADD CONSTRAINT chk_pric_default_coefficient_non_negative
        CHECK (category_default_coefficient_snapshot IS NULL
               OR category_default_coefficient_snapshot >= 0);
