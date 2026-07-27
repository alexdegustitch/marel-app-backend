-- =============================================================================
-- work_logs — historical snapshot of the compensation-scheme resolution
-- =============================================================================
-- A work log must be reconstructible without reading the employee's CURRENT
-- scheme or the CURRENT rule set. Moving an employee to a different scheme, or
-- editing a rule, must not change what already-recorded work was worth.
--
-- THREE CATEGORY CONCEPTS LIVE ON THIS TABLE AND MUST NOT BE MERGED
--
--   work_code_category_id
--       THE SOURCE CATEGORY — what the employee actually worked. Entered by the
--       user, never overwritten by any recalculation. This column already
--       existed and already had exactly this meaning; nothing about it changes,
--       it is only now documented as such.
--
--   scheme_effective_work_code_category_id                          (NEW)
--       THE SCHEME-EFFECTIVE CATEGORY — the category the employee-specific base
--       calculation uses after applying the compensation scheme. NULL means the
--       scheme did not remap anything, i.e. it equals the source category. For a
--       FOREIGN_FIXED_COEFFICIENT employee working J or D this is
--       FOREIGN_ALL_SHIFTS.
--
--   effective_work_code_category_id
--       THE DERIVED / CONTEXTUAL CATEGORY — the reversible night/weekend bonus
--       remap produced by work_code_category_mappings, recomputed on every
--       recalc. It is resolved from the SOURCE category and is NOT affected by
--       the compensation scheme: a fixed coefficient must not silently delete a
--       night mapping. This column already existed; its meaning is unchanged.
--
-- The other two new columns record WHICH scheme and WHICH rule produced the
-- result, so an old calculation can be explained years later even after the rule
-- has been superseded.
--
-- norm_multiplier_snapshot (already present, previously never written by the
-- backend) becomes the resolved coefficient snapshot: the scheme rule's
-- coefficient_override when one applied, otherwise the category's own
-- norm_multiplier. Its existing NUMERIC(38,2) type and scale are kept.
--
-- AUDITING: unlike effective_work_code_category_id, these columns are NOT
-- excluded from audit_trigger_fn. A change to the scheme, the rule or the
-- coefficient of a work log changes what it is worth, and that is exactly the
-- kind of change the audit log exists for. audit_trigger_fn only writes on a
-- real value change, so a recalc that resolves to the same values stays silent.
--
-- All columns are NULLABLE: existing rows keep NULL, which the resolver reads as
-- "recorded before compensation schemes existed" and treats as the standard
-- behaviour. No historical row is rewritten by this migration.
--
-- Re-runnable: IF NOT EXISTS.
-- =============================================================================

ALTER TABLE work_logs
    ADD COLUMN IF NOT EXISTS compensation_scheme_id BIGINT,
    ADD COLUMN IF NOT EXISTS scheme_effective_work_code_category_id BIGINT,
    ADD COLUMN IF NOT EXISTS work_code_category_scheme_rule_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_work_logs_compensation_scheme') THEN
        ALTER TABLE work_logs
            ADD CONSTRAINT fk_work_logs_compensation_scheme
            FOREIGN KEY (compensation_scheme_id)
            REFERENCES compensation_schemes (id) ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_work_logs_scheme_effective_category') THEN
        ALTER TABLE work_logs
            ADD CONSTRAINT fk_work_logs_scheme_effective_category
            FOREIGN KEY (scheme_effective_work_code_category_id)
            REFERENCES work_code_categories (id) ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_work_logs_scheme_rule') THEN
        ALTER TABLE work_logs
            ADD CONSTRAINT fk_work_logs_scheme_rule
            FOREIGN KEY (work_code_category_scheme_rule_id)
            REFERENCES work_code_category_scheme_rules (id) ON DELETE RESTRICT;
    END IF;
END $$;

COMMENT ON COLUMN work_logs.work_code_category_id IS
    'THE SOURCE CATEGORY: the work-code category the user actually selected. Never overwritten by recalculation and never replaced by a scheme-effective or contextually mapped category.';
COMMENT ON COLUMN work_logs.scheme_effective_work_code_category_id IS
    'Scheme-effective category used by the employee-specific base calculation. NULL = same as the source category. Distinct from effective_work_code_category_id, which is the reversible night/weekend bonus remap.';
COMMENT ON COLUMN work_logs.compensation_scheme_id IS
    'The compensation scheme in force for this employee ON THIS WORK DATE, snapshotted so the calculation never depends on the employee''s current scheme.';
COMMENT ON COLUMN work_logs.work_code_category_scheme_rule_id IS
    'The work_code_category_scheme_rules row that produced the effective category and coefficient, or NULL when no explicit rule applied.';
COMMENT ON COLUMN work_logs.norm_multiplier_snapshot IS
    'The resolved coefficient in force when the work was recorded: the scheme rule''s coefficient_override when one applied, otherwise work_code_categories.norm_multiplier.';

-- The payroll/report reads that group by the scheme-effective category.
CREATE INDEX IF NOT EXISTS idx_work_logs_scheme_effective_category
    ON work_logs (scheme_effective_work_code_category_id)
    WHERE scheme_effective_work_code_category_id IS NOT NULL;
