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
-- the line. Only real editing does that, and only per employee.
--
-- THE COLUMN-AGAINST-LINE VERDICT IS GONE FROM HERE, because the columns are.
-- The mirror on payroll_run_items was dropped once the two had been shown to
-- agree — meal and transport in 2026-09-04, the nine bonus columns in
-- 2026-09-06 — so there is no second figure left to disagree with. What remains
-- is what the line says and whether a person put it there.
--
-- WHAT TO DO BEFORE RUNNING IT, on the month you are checking:
--   1. change the meal price          → MEAL_ALLOWANCE.unit_amount
--   2. type a transport total          → TRANSPORT_ALLOWANCE.amount, is_overridden
--   3. change the base bonus           → MONTHLY_BONUS.amount moves, correction does not
--   4. change the additional bonus     → correction_amount moves and the total with it
--   5. open the payslip and read the figures
-- Each one is a separate path. Doing four of the five proves four of the five.
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
