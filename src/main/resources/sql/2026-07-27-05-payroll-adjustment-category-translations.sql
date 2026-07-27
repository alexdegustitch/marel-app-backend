-- =============================================================================
-- payroll_adjustment_category_translations — translated display names for the
-- payroll adjustment master categories
-- =============================================================================
-- Same shape and the same reasoning as work_code_category_translations.
--
-- CRITICALLY: payroll_adjustments (the transactional rows) get NO translated
-- name column. A payroll adjustment's display name is resolved through
--   payroll_adjustments.payroll_adjustment_category_id
--     -> payroll_adjustment_categories
--     -> payroll_adjustment_category_translations
-- Copying a translated name onto every adjustment row would duplicate master
-- data across thousands of transactional records and guarantee they diverge the
-- first time an administrator fixes a typo.
--
-- payroll_adjustment_categories.name stays the default and the fallback; sr-Latn
-- is served from it and is not seeded here.
--
-- Note there is no description column on the master table, so this table has no
-- description column either — nothing to translate that does not exist.
--
-- Re-runnable: IF NOT EXISTS, seeds guarded on (category, locale).
-- =============================================================================

CREATE TABLE IF NOT EXISTS payroll_adjustment_category_translations (
    id                             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payroll_adjustment_category_id BIGINT       NOT NULL,
    locale                         VARCHAR(35)  NOT NULL,
    name                           VARCHAR(255) NOT NULL,
    created_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ,

    CONSTRAINT fk_pact_category
        FOREIGN KEY (payroll_adjustment_category_id)
        REFERENCES payroll_adjustment_categories (id) ON DELETE CASCADE,

    CONSTRAINT chk_pact_locale_not_empty
        CHECK (length(trim(locale)) > 0),
    CONSTRAINT chk_pact_name_not_empty
        CHECK (length(trim(name)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pact_category_locale
    ON payroll_adjustment_category_translations (payroll_adjustment_category_id, lower(locale));

CREATE INDEX IF NOT EXISTS idx_pact_locale
    ON payroll_adjustment_category_translations (lower(locale));

DROP TRIGGER IF EXISTS trg_03_pact_updated_at ON payroll_adjustment_category_translations;
CREATE TRIGGER trg_03_pact_updated_at
    BEFORE UPDATE ON payroll_adjustment_category_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE payroll_adjustment_category_translations IS
    'Translated display names for payroll_adjustment_categories. The code is never translated. payroll_adjustment_categories.name remains the default and the fallback. payroll_adjustments never stores a translated name.';


-- =============================================================================
-- English seeds, keyed on the stable code
-- =============================================================================
INSERT INTO payroll_adjustment_category_translations (payroll_adjustment_category_id, locale, name)
SELECT c.id, 'en', v.name_en
FROM payroll_adjustment_categories c
JOIN (VALUES
        ('MEAL_ALLOWANCE',               'Meal allowance'),
        ('TRANSPORT_ALLOWANCE',          'Transport allowance'),
        ('OTHER',                        'Other'),
        ('FIXED_SALARY',                 'Fixed salary'),
        ('MONTHLY_BONUS',                'Monthly bonus'),
        ('POSITIVE_NEGATIVE_CORRECTION', 'Positive / negative correction'),
        ('INSTALLMENT',                  'Instalment'),
        ('PHONE_CURRENT_MONTH',          'Phone - current month'),
        ('PHONE_PREVIOUS_MONTH',         'Phone - previous month'),
        ('PAID_PART_1',                  'Paid'),
        ('PAID_PART_2',                  'Paid - second part'),
        ('PAID_PREVIOUS_PERIOD',         'Paid in the previous period'),
        ('PREVIOUS_BALANCE',             'Previous balance')
     ) AS v(code, name_en)
  ON c.code = v.code
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_adjustment_category_translations t
    WHERE t.payroll_adjustment_category_id = c.id AND lower(t.locale) = 'en'
);


-- =============================================================================
-- Audit
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'payroll_adjustment_category_translations'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'payroll_adjustment_category_translations');

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_adjustment_category_translations ON payroll_adjustment_category_translations;
CREATE TRIGGER trg_audit_logs_payroll_adjustment_category_translations
    AFTER INSERT OR UPDATE OR DELETE ON payroll_adjustment_category_translations
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
