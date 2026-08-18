-- =============================================================================
-- employees.preferred_locale — the language of an employee's own documents
-- =============================================================================
-- WHY A NEW COLUMN RATHER THAN REUSING AN EXISTING ONE
-- The repository already has user_preferences.language, but that is the language
-- of the APPLICATION for a logged-in user. A payroll PDF is a document about an
-- EMPLOYEE, and employees are not users — most have no account at all. Rendering
-- an employee's payslip in whatever language the clerk generating it happens to
-- prefer is not the same requirement, so the two stay separate.
--
-- LOCALE IS NOT NATIONALITY AND NOT A COMPENSATION SCHEME
-- This column is explicitly NOT derived from is_foreigner and NOT derived from
-- the compensation scheme. "Foreign employee therefore English" is wrong: a
-- foreign employee may read Serbian and a domestic one may want English. The
-- language is chosen explicitly, one field, one meaning.
--
-- DEFAULT sr-Latn preserves current behaviour for every existing row: nothing
-- changes language until someone sets it.
--
-- Re-runnable: IF NOT EXISTS.
-- =============================================================================

ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS preferred_locale VARCHAR(35) NOT NULL DEFAULT 'sr-Latn';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_preferred_locale') THEN
        ALTER TABLE employees
            ADD CONSTRAINT chk_employees_preferred_locale
            CHECK (preferred_locale IN ('sr-Latn', 'en'));
    END IF;
END $$;

COMMENT ON COLUMN employees.preferred_locale IS
    'Language for documents produced FOR this employee (payroll PDF). Independent of is_foreigner, of citizenship and of the compensation scheme. Never affects any calculated amount.';
