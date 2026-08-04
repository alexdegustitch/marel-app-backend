-- =============================================================================
-- Verify one employee's month after editing it through the screen
-- =============================================================================
-- READ ONLY. Run it right after you have edited somebody's payroll, and it says
-- whether what you did landed where the calculation now reads it from.
--
--   psql -d marel_app -v emp=17 -v period="'2026-08-01'" \
--        -f docs/business-rules/payroll-verify-one-employee.sql
--
-- WHY THIS EXISTS. The step-3 sweep recalculated every item and proved the READ
-- path: the calculation takes the override state off payroll_adjustments. It
-- proves nothing about the WRITE path — that an edit made on the screen reaches
-- the line rather than only the item column it used to go to. Only real editing
-- does that, and only per employee.
--
-- WHAT TO DO BEFORE RUNNING IT, on the month you are checking:
--   1. change the meal price
--   2. type a transport total
--   3. change the base bonus, and the additional bonus
--   4. open the payslip and read the figures
-- Each one is a separate path that step 2 moved. Doing three of the four proves
-- three of the four.
-- =============================================================================

\echo '=== Who and when'
SELECT i.id AS item_id, e.full_name, i.period, i.status,
       i.needs_recalculation
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
WHERE i.employee_id = :emp AND i.period = :period AND i.archived_at IS NULL;


\echo ''
\echo '=== Did the calculation actually run over these lines'
-- calculated_at NULL means the line has never been through the component
-- calculator, so everything below would be comparing against stale data.
SELECT c.code, a.calculated_at,
       a.is_applied      AS applied,
       a.has_manual_input AS somebody_entered_it,
       a.is_overridden   AS total_typed_in,
       a.override_reason
FROM payroll_adjustments a
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE a.payroll_run_item_id = (SELECT id FROM payroll_run_items
                               WHERE employee_id = :emp AND period = :period AND archived_at IS NULL)
  AND c.code IN ('MEAL_ALLOWANCE', 'TRANSPORT_ALLOWANCE', 'MONTHLY_BONUS')
ORDER BY c.code;


\echo ''
\echo '=== What the calculation produced, beside what applies'
SELECT c.code,
       a.system_quantity    AS sys_qty,
       a.quantity           AS qty,
       a.system_unit_amount AS sys_unit,
       a.unit_amount        AS unit,
       a.system_amount      AS sys_amount,
       a.amount             AS amount,
       a.system_correction_amount AS sys_correction,
       a.correction_amount        AS correction
FROM payroll_adjustments a
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE a.payroll_run_item_id = (SELECT id FROM payroll_run_items
                               WHERE employee_id = :emp AND period = :period AND archived_at IS NULL)
  AND c.code IN ('MEAL_ALLOWANCE', 'TRANSPORT_ALLOWANCE', 'MONTHLY_BONUS')
ORDER BY c.code;


\echo ''
\echo '=== THE VERDICT — column against line, for this one month'
-- Every row must read OK. A MISMATCH means the edit went to one of them and not
-- the other, which is exactly what must not survive into the column being dropped.
WITH i AS (
    SELECT * FROM payroll_run_items
    WHERE employee_id = :emp AND period = :period AND archived_at IS NULL
), line AS (
    SELECT c.code, a.*
    FROM payroll_adjustments a
    JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
    WHERE a.payroll_run_item_id = (SELECT id FROM i)
)
SELECT 'meal price'      AS figure,
       (SELECT meal_allowance_unit_amount FROM i)::text AS column_says,
       COALESCE((SELECT COALESCE(unit_amount, system_unit_amount, 0) FROM line WHERE code = 'MEAL_ALLOWANCE'), 0)::text AS line_says,
       CASE WHEN COALESCE((SELECT meal_allowance_unit_amount FROM i), 0)
                 = COALESCE((SELECT COALESCE(unit_amount, system_unit_amount, 0) FROM line WHERE code = 'MEAL_ALLOWANCE'), 0)
            THEN 'OK' ELSE '>>> MISMATCH' END AS verdict
UNION ALL
SELECT 'meal total',
       (SELECT total_meal_allowance_amount FROM i)::text,
       COALESCE((SELECT amount FROM line WHERE code = 'MEAL_ALLOWANCE'), 0)::text,
       CASE WHEN COALESCE((SELECT total_meal_allowance_amount FROM i), 0)
                 = COALESCE((SELECT amount FROM line WHERE code = 'MEAL_ALLOWANCE'), 0)
            THEN 'OK' ELSE '>>> MISMATCH' END
UNION ALL
SELECT 'transport total',
       (SELECT total_transport_allowance_amount FROM i)::text,
       COALESCE((SELECT amount FROM line WHERE code = 'TRANSPORT_ALLOWANCE'), 0)::text,
       CASE WHEN COALESCE((SELECT total_transport_allowance_amount FROM i), 0)
                 = COALESCE((SELECT amount FROM line WHERE code = 'TRANSPORT_ALLOWANCE'), 0)
            THEN 'OK' ELSE '>>> MISMATCH' END
UNION ALL
SELECT 'bonus base',
       (SELECT base_bonus_amount FROM i)::text,
       COALESCE((SELECT amount - correction_amount FROM line WHERE code = 'MONTHLY_BONUS'), 0)::text,
       CASE WHEN COALESCE((SELECT base_bonus_amount FROM i), 0)
                 = COALESCE((SELECT amount - correction_amount FROM line WHERE code = 'MONTHLY_BONUS'), 0)
            THEN 'OK' ELSE '>>> MISMATCH' END
UNION ALL
SELECT 'bonus additional',
       (SELECT bonus_correction_amount FROM i)::text,
       COALESCE((SELECT correction_amount FROM line WHERE code = 'MONTHLY_BONUS'), 0)::text,
       CASE WHEN COALESCE((SELECT bonus_correction_amount FROM i), 0)
                 = COALESCE((SELECT correction_amount FROM line WHERE code = 'MONTHLY_BONUS'), 0)
            THEN 'OK' ELSE '>>> MISMATCH' END
UNION ALL
SELECT 'bonus total',
       (SELECT total_bonus_amount FROM i)::text,
       COALESCE((SELECT amount FROM line WHERE code = 'MONTHLY_BONUS'), 0)::text,
       CASE WHEN COALESCE((SELECT total_bonus_amount FROM i), 0)
                 = COALESCE((SELECT amount FROM line WHERE code = 'MONTHLY_BONUS'), 0)
            THEN 'OK' ELSE '>>> MISMATCH' END;


\echo ''
\echo '=== Was anything actually edited? An untouched month proves nothing.'
-- has_manual_input is set by every edit path. If all three are false, the month
-- was only recalculated, and the WRITE path has not been exercised here.
SELECT count(*) FILTER (WHERE a.has_manual_input) AS lines_a_person_edited,
       count(*)                                   AS lines_checked
FROM payroll_adjustments a
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE a.payroll_run_item_id = (SELECT id FROM payroll_run_items
                               WHERE employee_id = :emp AND period = :period AND archived_at IS NULL)
  AND c.code IN ('MEAL_ALLOWANCE', 'TRANSPORT_ALLOWANCE', 'MONTHLY_BONUS');
