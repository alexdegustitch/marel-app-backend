-- =============================================================================
-- A compensation scheme cannot be activated with a gap in its matrix
-- =============================================================================
-- WHAT THIS CLOSES
-- The Phase 5 lifecycle: a new scheme is created inactive, gets a rule for every
-- active adjustment category, and only THEN may it be activated and assigned.
-- Nothing enforced the last step. The resolver throws at calculation time and
-- PayrollConfigurationValidationService reports the gap, but both are after the
-- fact: somebody can activate a scheme on Tuesday and meet the exception on
-- Friday, under an employee's name.
--
-- WHY A TRIGGER AND NOT A SERVICE GUARD
-- The adjustment-category half of this rule lives in
-- PayrollAdjustmentCategoryService, because categories are created and activated
-- through the application. Schemes are not: CompensationSchemeController is
-- read-only and NewCompensationSchemeIsDataOnlyIT exists to keep it that way — a
-- new worker type is rows in three tables and no Java change. A rule about
-- activation therefore has to live where activation happens, which is SQL.
--
-- WHAT IT REFUSES: setting is_active to TRUE while any active, unarchived
-- adjustment category has no rule for this scheme. The message NAMES the missing
-- categories, because "the matrix is incomplete" is a fact and the list is a
-- task.
--
-- WHAT IT ALLOWS, deliberately:
--   * creating a scheme inactive, and editing an inactive one
--   * a scheme that is ALREADY active staying active — only the TRANSITION is
--     checked, so an unrelated edit to a scheme whose matrix predates this rule
--     does not become impossible to save
--   * deactivating, always
--
-- Re-runnable.
-- =============================================================================

CREATE OR REPLACE FUNCTION compensation_scheme_activation_needs_full_matrix()
RETURNS TRIGGER AS $$
DECLARE
    v_missing TEXT;
BEGIN
    SELECT string_agg(c.code, ', ' ORDER BY c.code) INTO v_missing
    FROM payroll_adjustment_categories c
    WHERE c.is_active
      AND c.archived_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM payroll_adjustment_category_scheme_rules r
          WHERE r.compensation_scheme_id = NEW.id
            AND r.payroll_adjustment_category_id = c.id
            AND r.archived_at IS NULL);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'Način obračuna "%" ne može da se aktivira dok nema pravilo za svaku '
            'aktivnu stavku. Nedostaje: %.', NEW.code, v_missing;
    END IF;

    RETURN NEW;
END $$ LANGUAGE plpgsql;

COMMENT ON FUNCTION compensation_scheme_activation_needs_full_matrix() IS
    'Phase 5 lifecycle: a scheme may only be activated once every active adjustment category has a rule for it. A missing rule is not "no restriction" — the resolver throws — so an active scheme with a gap stops the payroll of everybody on it.';

-- TWO TRIGGERS, because TG_OP is not available inside a WHEN clause — only in the
-- function body, which is too late to skip the call. Splitting them is also what
-- lets the UPDATE side test OLD, which the INSERT side has none of.
DROP TRIGGER IF EXISTS trg_compensation_scheme_activation ON compensation_schemes;
DROP TRIGGER IF EXISTS trg_compensation_scheme_activation_insert ON compensation_schemes;
DROP TRIGGER IF EXISTS trg_compensation_scheme_activation_update ON compensation_schemes;

CREATE TRIGGER trg_compensation_scheme_activation_insert
    BEFORE INSERT ON compensation_schemes
    FOR EACH ROW
    WHEN (NEW.is_active)
    EXECUTE FUNCTION compensation_scheme_activation_needs_full_matrix();

CREATE TRIGGER trg_compensation_scheme_activation_update
    BEFORE UPDATE ON compensation_schemes
    FOR EACH ROW
    -- Only the transition INTO active: a scheme that is already active and stays
    -- active is left alone, so an unrelated edit to one whose matrix predates this
    -- rule does not become impossible to save.
    WHEN (NEW.is_active AND NOT OLD.is_active)
    EXECUTE FUNCTION compensation_scheme_activation_needs_full_matrix();

DO $$
DECLARE
    v_incomplete INTEGER;
BEGIN
    SELECT count(*) INTO v_incomplete
    FROM compensation_schemes s
    WHERE s.is_active AND s.archived_at IS NULL
      AND EXISTS (
          SELECT 1 FROM payroll_adjustment_categories c
          WHERE c.is_active AND c.archived_at IS NULL
            AND NOT EXISTS (
                SELECT 1 FROM payroll_adjustment_category_scheme_rules r
                WHERE r.compensation_scheme_id = s.id
                  AND r.payroll_adjustment_category_id = c.id
                  AND r.archived_at IS NULL));

    IF v_incomplete > 0 THEN
        -- Reported, not fixed and not raised: these schemes are already active and
        -- deactivating them would stop somebody's payroll to enforce a rule about
        -- starting one. The report finds them; this trigger stops the next one.
        RAISE NOTICE '% already-active scheme(s) have an incomplete matrix. The trigger does not '
            'touch them — see GET /api/payroll-maintenance/configuration-report.', v_incomplete;
    ELSE
        RAISE NOTICE 'Every active compensation scheme has a rule for every active category.';
    END IF;
END $$;
