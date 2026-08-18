-- =============================================================================
-- Per-scheme payroll adjustment availability, and the no-bonus flag
-- =============================================================================
-- Two separate things a compensation scheme has to be able to say about the
-- payroll sheet, kept separate because they are separate:
--
--   payroll_adjustment_category_scheme_rules  which ADJUSTMENT LINES appear
--   compensation_schemes.allows_performance_bonus  whether worked categories
--                                                  earn a bonus on top
--
-- =============================================================================
-- WHY THIS TABLE DEFAULTS TO *ALLOW*, UNLIKE work_code_category_scheme_rules
-- =============================================================================
-- For a WORK category, "no rule" means "unknown coefficient", so the restricted
-- scheme must refuse it — being wrong there silently misprices work.
--
-- An adjustment category is a labelled amount on a payslip. If this table were
-- closed by default, every adjustment category added in the future would
-- silently vanish for foreign employees until somebody remembered to add a row,
-- and a missing payslip line is much harder to notice than an extra one. So the
-- default is ALLOW and only the exclusions are stored.
--
-- The two tables therefore look similar and behave oppositely on purpose. Each
-- default is the safe direction for what that table controls.
-- =============================================================================

ALTER TABLE compensation_schemes
    ADD COLUMN IF NOT EXISTS allows_performance_bonus BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN compensation_schemes.allows_performance_bonus IS
    'FALSE zeroes payroll_run_item_categories.bonus_amount for this scheme. Efficiency still drives the calculation itself — approved performance weights the minutes that become effective_minutes — this only removes the bonus paid on top of them.';

CREATE TABLE IF NOT EXISTS payroll_adjustment_category_scheme_rules (
    id                             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    compensation_scheme_id         BIGINT      NOT NULL,
    payroll_adjustment_category_id BIGINT      NOT NULL,
    -- The only reason a row exists is usually to say FALSE. TRUE rows are legal
    -- and are simply explicit statements of the default.
    is_allowed                     BOOLEAN     NOT NULL DEFAULT TRUE,
    valid_from                     DATE        NOT NULL,
    valid_until                    DATE,
    note                           TEXT,
    is_active                      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ,
    archived_at                    TIMESTAMPTZ,

    CONSTRAINT fk_pacsr_scheme
        FOREIGN KEY (compensation_scheme_id) REFERENCES compensation_schemes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pacsr_category
        FOREIGN KEY (payroll_adjustment_category_id)
        REFERENCES payroll_adjustment_categories (id) ON DELETE RESTRICT,

    CONSTRAINT chk_pacsr_validity
        CHECK (valid_until IS NULL OR valid_until >= valid_from),
    CONSTRAINT chk_pacsr_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_active = TRUE))
);

-- At most one in-force rule per scheme + adjustment category at any time.
ALTER TABLE payroll_adjustment_category_scheme_rules
    DROP CONSTRAINT IF EXISTS ex_pacsr_no_overlap;
ALTER TABLE payroll_adjustment_category_scheme_rules
    ADD CONSTRAINT ex_pacsr_no_overlap
    EXCLUDE USING gist (
        compensation_scheme_id WITH =,
        payroll_adjustment_category_id WITH =,
        daterange(valid_from,
                  CASE WHEN valid_until IS NULL THEN NULL ELSE valid_until + 1 END) WITH &&
    ) WHERE (archived_at IS NULL AND is_active = TRUE);

CREATE INDEX IF NOT EXISTS idx_pacsr_lookup
    ON payroll_adjustment_category_scheme_rules
       (compensation_scheme_id, valid_from, valid_until)
    WHERE archived_at IS NULL AND is_active = TRUE;

DROP TRIGGER IF EXISTS trg_02_pacsr_archived_at ON payroll_adjustment_category_scheme_rules;
CREATE TRIGGER trg_02_pacsr_archived_at
    BEFORE UPDATE ON payroll_adjustment_category_scheme_rules
    FOR EACH ROW EXECUTE FUNCTION set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_pacsr_updated_at ON payroll_adjustment_category_scheme_rules;
CREATE TRIGGER trg_03_pacsr_updated_at
    BEFORE UPDATE ON payroll_adjustment_category_scheme_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE payroll_adjustment_category_scheme_rules IS
    'Per-compensation-scheme availability of a payroll adjustment category. ABSENT means ALLOWED — the opposite default from work_code_category_scheme_rules, because a silently missing payslip line is worse than an extra one.';


-- =============================================================================
-- Seeds
-- =============================================================================
DO $$
DECLARE
    v_from   CONSTANT DATE := DATE '2026-08-01';
    v_scheme BIGINT;
BEGIN
    SELECT id INTO v_scheme FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT';
    IF v_scheme IS NULL THEN
        RAISE EXCEPTION 'FOREIGN_FIXED_COEFFICIENT missing; run 2026-07-27-01 first';
    END IF;

    -- No bonus of any kind for this scheme: neither the monthly bonus line nor
    -- the per-category bonus paid on top of efficiency.
    UPDATE compensation_schemes
    SET allows_performance_bonus = FALSE
    WHERE id = v_scheme AND allows_performance_bonus IS DISTINCT FROM FALSE;

    -- The five excluded adjustment lines. Everything else is allowed by default.
    INSERT INTO payroll_adjustment_category_scheme_rules
        (compensation_scheme_id, payroll_adjustment_category_id, is_allowed, valid_from, note)
    SELECT v_scheme, c.id, FALSE, v_from, v.reason
    FROM payroll_adjustment_categories c
    JOIN (VALUES
            ('MONTHLY_BONUS',        'No bonuses under this scheme.'),
            ('PHONE_CURRENT_MONTH',  'Not applicable under this scheme.'),
            ('PHONE_PREVIOUS_MONTH', 'Not applicable under this scheme.'),
            ('MEAL_ALLOWANCE',       'No meal allowance under this scheme.'),
            ('TRANSPORT_ALLOWANCE',  'Transport is not paid under this scheme.')
         ) AS v(code, reason) ON v.code = c.code
    WHERE NOT EXISTS (
        SELECT 1 FROM payroll_adjustment_category_scheme_rules r
        WHERE r.compensation_scheme_id = v_scheme
          AND r.payroll_adjustment_category_id = c.id
          AND r.valid_from = v_from);

    RAISE NOTICE 'FOREIGN_FIXED_COEFFICIENT: % adjustment categories excluded, performance bonus disabled',
        (SELECT count(*) FROM payroll_adjustment_category_scheme_rules
         WHERE compensation_scheme_id = v_scheme AND is_allowed = FALSE);
END $$;


-- =============================================================================
-- Audit
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'payroll_adjustment_category_scheme_rules'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables
                  WHERE table_name = 'payroll_adjustment_category_scheme_rules');

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_adjustment_category_scheme_rules
    ON payroll_adjustment_category_scheme_rules;
CREATE TRIGGER trg_audit_logs_payroll_adjustment_category_scheme_rules
    AFTER INSERT OR UPDATE OR DELETE ON payroll_adjustment_category_scheme_rules
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
