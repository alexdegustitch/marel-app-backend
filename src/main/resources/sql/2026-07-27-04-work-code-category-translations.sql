-- =============================================================================
-- work_code_category_translations — translated display names for one master
-- reference table
-- =============================================================================
-- SCOPE, deliberately narrow: work_code_categories is master/reference data whose
-- NAMES are business content maintained by administrators, so a translated name
-- belongs in the database. Transactional records (work_logs, daily_report_
-- categories, payroll_run_item_categories) reference the master row and resolve
-- the name through it — they never carry a copy of a translated name.
--
-- The word is "translation", not "transcription". Nothing here transliterates.
--
-- DEFAULT AND FALLBACK
-- work_code_categories.category_name stays the default name and the fallback.
-- Resolution is COALESCE(translation.name, category.category_name), so a missing
-- English row yields the Serbian name rather than NULL.
--
-- sr-Latn is deliberately NOT seeded. It is served from category_name, so there
-- is exactly one place to edit a Serbian name and nothing to drift out of sync.
-- An explicit sr-Latn row is still legal if an administrator ever needs to
-- override the master name for that locale.
--
-- ON DELETE CASCADE is correct here and only here: a translation has no meaning
-- without its category, and unlike a payroll adjustment it is not a historical
-- business record.
--
-- Re-runnable: IF NOT EXISTS, seeds guarded on (category, locale).
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_code_category_translations (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_code_category_id  BIGINT       NOT NULL,
    -- BCP-47-ish tag. Stored as given; compared case-insensitively by the
    -- unique index below so 'EN' and 'en' cannot both exist.
    locale                 VARCHAR(35)  NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,

    CONSTRAINT fk_wcct_category
        FOREIGN KEY (work_code_category_id) REFERENCES work_code_categories (id) ON DELETE CASCADE,

    CONSTRAINT chk_wcct_locale_not_empty
        CHECK (length(trim(locale)) > 0),
    CONSTRAINT chk_wcct_name_not_empty
        CHECK (length(trim(name)) > 0)
);

-- One translation per category per locale.
CREATE UNIQUE INDEX IF NOT EXISTS uq_wcct_category_locale
    ON work_code_category_translations (work_code_category_id, lower(locale));

-- The read path is "give me every category's name in locale X" — one index scan
-- rather than one query per category.
CREATE INDEX IF NOT EXISTS idx_wcct_locale
    ON work_code_category_translations (lower(locale));

DROP TRIGGER IF EXISTS trg_03_wcct_updated_at ON work_code_category_translations;
CREATE TRIGGER trg_03_wcct_updated_at
    BEFORE UPDATE ON work_code_category_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE work_code_category_translations IS
    'Translated display names for work_code_categories. category_no (the code) is never translated. work_code_categories.category_name remains the default and the fallback.';


-- =============================================================================
-- English seeds — only where the Serbian name has an unambiguous English
-- equivalent. Anything not listed here falls back to the Serbian name and is
-- reported as still needing a translation.
-- =============================================================================
INSERT INTO work_code_category_translations (work_code_category_id, locale, name)
SELECT c.id, 'en', v.name_en
FROM work_code_categories c
JOIN (VALUES
        ('B',                  'Sick leave'),
        ('B30',                'Sick leave over 30 days'),
        ('BP',                 'Sick leave - work injury'),
        ('D',                  '3rd shift'),
        ('DB',                 '3rd shift bonus'),
        ('G',                  'Electroplating'),
        ('GB',                 'Electroplating bonus'),
        ('J',                  '1st, 2nd shift'),
        ('JB',                 '1st, 2nd shift bonus'),
        ('L',                  'Caster - 1st, 2nd shift'),
        ('L3',                 'Caster - 3rd shift'),
        ('LP',                 'Caster assistant - 1st, 2nd shift'),
        ('LP3',                'Caster assistant - 3rd shift'),
        ('PL',                 'Plastics - 1 or 2 machines'),
        ('PLB',                'Plastics - 3 or 4 machines'),
        ('Z',                  'Welding'),
        ('ZB',                 'Welding bonus'),
        ('ND',                 'Non-working day'),
        ('GO',                 'Annual leave'),
        ('NO',                 'Unpaid leave'),
        ('SO',                 'Authorised absence'),
        ('PLO',                'Paid leave'),
        -- The common effective category, under both codes it has had: created as
        -- FOREIGN_ALL_SHIFTS here, renamed to S by 2026-07-27-09. Only one of the
        -- two ever matches a row, and the join is on the code, so listing both is
        -- safe and keeps this script correct whichever order it runs in.
        ('FOREIGN_ALL_SHIFTS', '1st, 2nd and 3rd shift'),
        ('S',                  '1st, 2nd and 3rd shift')
     ) AS v(category_no, name_en)
  ON lower(c.category_no) = lower(v.category_no)
WHERE NOT EXISTS (
    SELECT 1 FROM work_code_category_translations t
    WHERE t.work_code_category_id = c.id AND lower(t.locale) = 'en'
);


-- =============================================================================
-- Audit — administrators edit these names and payroll documents display them
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'work_code_category_translations'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'work_code_category_translations');

DROP TRIGGER IF EXISTS trg_audit_logs_work_code_category_translations ON work_code_category_translations;
CREATE TRIGGER trg_audit_logs_work_code_category_translations
    AFTER INSERT OR UPDATE OR DELETE ON work_code_category_translations
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
