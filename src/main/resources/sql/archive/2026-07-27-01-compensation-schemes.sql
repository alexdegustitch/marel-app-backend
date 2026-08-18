-- =============================================================================
-- compensation_schemes — the payroll calculation policy an employee is under
-- =============================================================================
-- WHY THIS EXISTS
-- Some employees are paid under a policy that differs from the standard one:
-- their work-code category selection is restricted and their base coefficient is
-- fixed regardless of which shift they worked. Before this table that distinction
-- had nowhere to live except employees.is_foreigner, which is a *personnel*
-- attribute, not a payroll rule.
--
-- Nationality, citizenship, language and payroll policy are four separate
-- concepts and this schema keeps them separate:
--   employees.is_foreigner        who the person is (unchanged, untouched)
--   employees.preferred_locale    what language their documents are in
--   compensation_schemes          how their work is priced
--
-- The scheme is deliberately NOT a column on employees: an employee moves
-- between schemes over time and historical payroll must not change when they do.
-- See 2026-07-27-02-employee-compensation-scheme-history.sql.
--
-- allow_unmapped_categories is the whole behavioural difference between the two
-- seeded schemes:
--   true  -> a source category with no explicit rule is allowed, resolves to
--            itself, and uses the normal coefficient logic (open by default)
--   false -> a source category with no explicit rule is NOT available and is
--            rejected on submission (closed by default)
--
-- Re-runnable: IF NOT EXISTS throughout, seeds are keyed on the stable code.
-- =============================================================================

CREATE TABLE IF NOT EXISTS compensation_schemes (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The stable business identifier. Application code and later migrations
    -- resolve schemes through this, never through a hard-coded id.
    code                      VARCHAR(60)  NOT NULL,
    name                      VARCHAR(150) NOT NULL,
    allow_unmapped_categories BOOLEAN      NOT NULL DEFAULT TRUE,
    note                      TEXT,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ,
    archived_at               TIMESTAMPTZ,

    CONSTRAINT uq_compensation_schemes_code UNIQUE (code),

    CONSTRAINT chk_compensation_schemes_code_not_empty
        CHECK (length(trim(code)) > 0),
    CONSTRAINT chk_compensation_schemes_name_not_empty
        CHECK (length(trim(name)) > 0),
    -- Same archive contract the other reference tables use: an archived row can
    -- never be active.
    CONSTRAINT chk_compensation_schemes_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_active = TRUE))
);

DROP TRIGGER IF EXISTS trg_02_compensation_schemes_archived_at ON compensation_schemes;
CREATE TRIGGER trg_02_compensation_schemes_archived_at
    BEFORE UPDATE ON compensation_schemes
    FOR EACH ROW EXECUTE FUNCTION set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_compensation_schemes_updated_at ON compensation_schemes;
CREATE TRIGGER trg_03_compensation_schemes_updated_at
    BEFORE UPDATE ON compensation_schemes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE compensation_schemes IS
    'Payroll calculation policy. An employee is attached to one scheme per date range via employee_compensation_scheme_history; the scheme is never a column on employees.';
COMMENT ON COLUMN compensation_schemes.allow_unmapped_categories IS
    'TRUE: a source category with no work_code_category_scheme_rules row is allowed and resolves to itself with normal coefficient logic. FALSE: it is unavailable and rejected on submission.';


-- =============================================================================
-- Seeds — resolved by code, never by id
-- =============================================================================
INSERT INTO compensation_schemes (code, name, allow_unmapped_categories, note)
SELECT 'STANDARD',
       'Standardni obračun',
       TRUE,
       'Default policy. Categories behave exactly as they did before compensation schemes existed: every active category is selectable and the coefficient comes from work_code_categories.norm_multiplier.'
WHERE NOT EXISTS (SELECT 1 FROM compensation_schemes WHERE code = 'STANDARD');

INSERT INTO compensation_schemes (code, name, allow_unmapped_categories, note)
SELECT 'FOREIGN_FIXED_COEFFICIENT',
       'Fiksni koeficijent',
       FALSE,
       'Restricted policy: only categories with an explicit work_code_category_scheme_rules row may be selected, and the shift categories resolve to a single effective category with coefficient 1.'
WHERE NOT EXISTS (SELECT 1 FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT');


-- =============================================================================
-- Audit — the existing mechanism, unchanged
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'compensation_schemes'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'compensation_schemes');

DROP TRIGGER IF EXISTS trg_audit_logs_compensation_schemes ON compensation_schemes;
CREATE TRIGGER trg_audit_logs_compensation_schemes
    AFTER INSERT OR UPDATE OR DELETE ON compensation_schemes
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
