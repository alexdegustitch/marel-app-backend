-- =============================================================================
-- What kind of worker somebody is comes only from their scheme
-- =============================================================================
-- THE PROBLEM
-- employees.is_foreigner and employees.works_in_commercial answered the same
-- question as the employee's compensation scheme, and answered it separately. No
-- calculator read either one — that was already deliberate, and stated in
-- WorkCategoryResolutionService, ComponentContext, CompensationSchemeCodes,
-- CompensationSchemeInitializer and PayrollRunItemService. But the screens read
-- them, so the application could show "Komercijala" for somebody payroll was
-- treating as STANDARD.
--
-- That was not hypothetical. Measured immediately before this migration:
--
--   is_foreigner=false works_in_commercial=false  STANDARD                   112
--   is_foreigner=true  works_in_commercial=false  FOREIGN_FIXED_COEFFICIENT   25
--   is_foreigner=false works_in_commercial=true   STANDARD                     1
--
-- So the columns go, and both traits are derived from the scheme instead. One
-- source of truth, and the drift becomes unrepresentable rather than merely
-- discouraged.
--
-- WHAT HAPPENS TO THE DATA
--   is_foreigner          25 of 25 agree with FOREIGN_FIXED_COEFFICIENT and 113
--                         of 113 agree with not-FOREIGN. The column is exactly
--                         reproducible from employee_compensation_scheme_history,
--                         so dropping it loses nothing.
--
--   works_in_commercial   One row was true: employee id 6, EMP-0006, whose
--                         scheme is and remains STANDARD. Nobody holds a
--                         COMMERCIAL period at all — 0 rows. The owner was asked
--                         directly and ruled the flag an error, not a missing
--                         scheme period, so this one value is discarded on
--                         purpose and no pay changes.
--
-- The screens keep both traits. "Stranac" badges, the employees filter tab with
-- its count, and the page identity in employeeIdentity.ts now resolve through
-- the active scheme instead of these columns.
--
-- Re-runnable.
-- =============================================================================

-- Recorded before the drop so the numbers above can still be checked afterwards
-- against a restored backup, rather than being taken on trust.
DO $$
DECLARE
    foreign_flagged   INTEGER;
    foreign_scheme    INTEGER;
    mismatched        INTEGER;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'employees' AND column_name = 'is_foreigner') THEN

        -- coalesce, not a bare comparison: an employee with NO scheme row yields
        -- NULL, and NULL is not counted by FILTER — so a flagged employee with a
        -- missing scheme would slip past the very guard meant to catch them.
        SELECT count(*) FILTER (WHERE e.is_foreigner),
               count(*) FILTER (WHERE coalesce(cs.code, '') = 'FOREIGN_FIXED_COEFFICIENT'),
               count(*) FILTER (WHERE e.is_foreigner
                                   <> (coalesce(cs.code, '') = 'FOREIGN_FIXED_COEFFICIENT'))
          INTO foreign_flagged, foreign_scheme, mismatched
          FROM employees e
          LEFT JOIN employee_compensation_scheme_history h
                 ON h.employee_id = e.id AND h.valid_until IS NULL AND h.archived_at IS NULL
          LEFT JOIN compensation_schemes cs ON cs.id = h.compensation_scheme_id;

        RAISE NOTICE 'is_foreigner=true: %, FOREIGN_FIXED_COEFFICIENT: %, disagreeing: %',
                     foreign_flagged, foreign_scheme, mismatched;

        -- A disagreement here means the scheme cannot reproduce the flag and
        -- dropping it WOULD lose information. Stop and let a human look.
        IF mismatched > 0 THEN
            RAISE EXCEPTION
                'Refusing to drop is_foreigner: % employee(s) disagree with their compensation scheme. Reconcile them first.',
                mismatched;
        END IF;
    END IF;
END $$;

ALTER TABLE employees DROP COLUMN IF EXISTS is_foreigner;
ALTER TABLE employees DROP COLUMN IF EXISTS works_in_commercial;
