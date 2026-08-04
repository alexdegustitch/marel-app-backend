-- =============================================================================
-- Step 3 — does the line say the same thing as the column it will replace?
-- =============================================================================
-- READ ONLY. Run this after a full payroll cycle on the dual-written model, and
-- read the verdict at the bottom before dropping a single column.
--
-- Step 2 made the LINE the source: the recalculation decides from
-- payroll_adjustments and writes the seventeen payroll_run_items columns from it.
-- While both are written, the two must agree on every item. Where they do not,
-- one of them is wrong and the difference is somebody's pay — which is the only
-- reason the columns were kept rather than dropped in the same change.
--
-- WHAT "DRIFT" MEANS HERE
-- A line that has never been through the component calculator will differ, and
-- that is not a fault — it is work not yet done. The test for it is
-- payroll_adjustments.calculated_at IS NULL, NOT payroll_run_items
-- .needs_recalculation: an item calculated a year ago under the old code is not
-- marked stale either, so that flag counts the wrong thing. Getting this wrong
-- reported six items as real drift when every one of them had calculated_at NULL.
--
-- HOW TO GET THE "NEVER CALCULATED" COUNT TO ZERO
--   UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE archived_at IS NULL;
-- then open each payroll month once, or let the recalc queue drain. That is the
-- "one verified full cycle" the plan asks for; nothing here does it for you,
-- because recalculating every item is a write and this file is a report.
-- =============================================================================

\echo '=== Q0. Lines never run through the component calculator — drift below is expected for these'
SELECT count(*) FILTER (WHERE a.calculated_at IS NULL) AS lines_never_calculated,
       count(*)                                        AS lines_total
FROM payroll_adjustments a
JOIN payroll_run_items i ON i.id = a.payroll_run_item_id
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE i.archived_at IS NULL
  AND c.code IN ('MEAL_ALLOWANCE', 'TRANSPORT_ALLOWANCE', 'MONTHLY_BONUS');


\echo ''
\echo '=== Q1. Meal — the price and the total'
-- unit_amount on the line is NULL until somebody sets one; the system price then
-- applies, which is what the column should hold. And system_unit_amount is itself
-- NULL whenever the calculator returned a plain zero rather than a quantity times
-- a price — so BOTH sides need a zero default, or 1052 items report a difference
-- between "no price" and "a price of nothing".
SELECT i.id                                        AS item_id,
       i.period,
       i.meal_allowance_unit_amount                AS column_unit,
       COALESCE(a.unit_amount, a.system_unit_amount, 0) AS line_unit,
       i.total_meal_allowance_amount               AS column_total,
       a.amount                                    AS line_total,
       i.meal_allowance_unit_amount_overridden     AS column_flag,
       (a.unit_amount IS NOT NULL
        AND a.unit_amount IS DISTINCT FROM a.system_unit_amount) AS line_says_human
FROM payroll_run_items i
JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE i.archived_at IS NULL
  AND c.code = 'MEAL_ALLOWANCE'
  AND (COALESCE(i.meal_allowance_unit_amount, 0)
           IS DISTINCT FROM COALESCE(a.unit_amount, a.system_unit_amount, 0)
    OR COALESCE(i.total_meal_allowance_amount, 0) IS DISTINCT FROM COALESCE(a.amount, 0))
ORDER BY i.period DESC, i.id;


\echo ''
\echo '=== Q2. Transport — the total and who set it'
SELECT i.id                                            AS item_id,
       i.period,
       i.total_transport_allowance_amount              AS column_total,
       a.amount                                        AS line_total,
       i.total_transport_allowance_amount_overridden   AS column_flag,
       (a.is_overridden
        OR (a.has_manual_input
            AND a.amount IS DISTINCT FROM a.system_amount))        AS line_says_human
FROM payroll_run_items i
JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE i.archived_at IS NULL
  AND c.code = 'TRANSPORT_ALLOWANCE'
  AND COALESCE(i.total_transport_allowance_amount, 0) IS DISTINCT FROM COALESCE(a.amount, 0)
ORDER BY i.period DESC, i.id;


\echo ''
\echo '=== Q3. Bonus — base, additional, total'
-- The line keeps `amount` as the effective TOTAL, so the base is amount minus
-- correction_amount. That is why the earnings sum never had to change.
SELECT i.id                                        AS item_id,
       i.period,
       i.base_bonus_amount                         AS column_base,
       (a.amount - a.correction_amount)            AS line_base,
       i.bonus_correction_amount                   AS column_additional,
       a.correction_amount                         AS line_additional,
       i.total_bonus_amount                        AS column_total,
       a.amount                                    AS line_total
FROM payroll_run_items i
JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
WHERE i.archived_at IS NULL
  AND c.code = 'MONTHLY_BONUS'
  AND (COALESCE(i.base_bonus_amount, 0)
           IS DISTINCT FROM COALESCE(a.amount, 0) - COALESCE(a.correction_amount, 0)
    OR COALESCE(i.bonus_correction_amount, 0) IS DISTINCT FROM COALESCE(a.correction_amount, 0)
    OR COALESCE(i.total_bonus_amount, 0)      IS DISTINCT FROM COALESCE(a.amount, 0))
ORDER BY i.period DESC, i.id;


\echo ''
\echo '=== Q4. THE VERDICT — how many items disagree, per family'
WITH meal AS (
    SELECT i.id FROM payroll_run_items i
    JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
    JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
    WHERE i.archived_at IS NULL AND c.code = 'MEAL_ALLOWANCE'
      AND (COALESCE(i.meal_allowance_unit_amount, 0)
               IS DISTINCT FROM COALESCE(a.unit_amount, a.system_unit_amount, 0)
        OR COALESCE(i.total_meal_allowance_amount, 0) IS DISTINCT FROM COALESCE(a.amount, 0))
), transport AS (
    SELECT i.id FROM payroll_run_items i
    JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
    JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
    WHERE i.archived_at IS NULL AND c.code = 'TRANSPORT_ALLOWANCE'
      AND COALESCE(i.total_transport_allowance_amount, 0) IS DISTINCT FROM COALESCE(a.amount, 0)
), bonus AS (
    SELECT i.id FROM payroll_run_items i
    JOIN payroll_adjustments a ON a.payroll_run_item_id = i.id
    JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
    WHERE i.archived_at IS NULL AND c.code = 'MONTHLY_BONUS'
      AND (COALESCE(i.base_bonus_amount, 0)
               IS DISTINCT FROM COALESCE(a.amount, 0) - COALESCE(a.correction_amount, 0)
        OR COALESCE(i.bonus_correction_amount, 0) IS DISTINCT FROM COALESCE(a.correction_amount, 0)
        OR COALESCE(i.total_bonus_amount, 0)      IS DISTINCT FROM COALESCE(a.amount, 0))
)
-- Split by whether the line has ever been calculated. Only the second column is
-- a problem; the first is a queue.
, all_drift AS (
    SELECT 'meal' AS family, id FROM meal
    UNION ALL SELECT 'transport', id FROM transport
    UNION ALL SELECT 'bonus', id FROM bonus
)
-- count(d.id), not count(*): the LEFT JOIN below yields one all-NULL row for a
-- family with nothing wrong, and count(*) counted that row — so a clean family
-- reported 1 forever, in the column meant to say "not checked yet".
SELECT f.family,
       count(d.id) FILTER (WHERE NOT EXISTS (
           SELECT 1 FROM payroll_adjustments a2
           JOIN payroll_adjustment_categories c2 ON c2.id = a2.payroll_adjustment_category_id
           WHERE a2.payroll_run_item_id = d.id AND a2.calculated_at IS NOT NULL
             AND c2.code = CASE d.family WHEN 'meal' THEN 'MEAL_ALLOWANCE'
                                         WHEN 'transport' THEN 'TRANSPORT_ALLOWANCE'
                                         ELSE 'MONTHLY_BONUS' END))  AS never_calculated,
       count(d.id) FILTER (WHERE EXISTS (
           SELECT 1 FROM payroll_adjustments a2
           JOIN payroll_adjustment_categories c2 ON c2.id = a2.payroll_adjustment_category_id
           WHERE a2.payroll_run_item_id = d.id AND a2.calculated_at IS NOT NULL
             AND c2.code = CASE d.family WHEN 'meal' THEN 'MEAL_ALLOWANCE'
                                         WHEN 'transport' THEN 'TRANSPORT_ALLOWANCE'
                                         ELSE 'MONTHLY_BONUS' END))  AS real_drift
FROM (VALUES ('meal'), ('transport'), ('bonus')) AS f(family)
LEFT JOIN all_drift d ON d.family = f.family
-- LEFT JOIN so a family with nothing wrong still prints a row. GROUP BY alone
-- made it vanish, which reads as "not checked" rather than "checked and clean".
GROUP BY f.family
ORDER BY f.family;


\echo ''
\echo '=== Q5. A line that is missing altogether'
-- A column with money in it and no line to hold it would lose that money the
-- moment the column goes. Rarer than drift and worse.
SELECT i.id AS item_id, i.period, c.code AS missing_line,
       CASE c.code
            WHEN 'MEAL_ALLOWANCE'      THEN i.total_meal_allowance_amount
            WHEN 'TRANSPORT_ALLOWANCE' THEN i.total_transport_allowance_amount
            ELSE i.total_bonus_amount
       END AS column_holds
FROM payroll_run_items i
CROSS JOIN (VALUES ('MEAL_ALLOWANCE'), ('TRANSPORT_ALLOWANCE'), ('MONTHLY_BONUS')) AS c(code)
WHERE i.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM payroll_adjustments a
      JOIN payroll_adjustment_categories cc ON cc.id = a.payroll_adjustment_category_id
      WHERE a.payroll_run_item_id = i.id AND cc.code = c.code)
  AND COALESCE(CASE c.code
            WHEN 'MEAL_ALLOWANCE'      THEN i.total_meal_allowance_amount
            WHEN 'TRANSPORT_ALLOWANCE' THEN i.total_transport_allowance_amount
            ELSE i.total_bonus_amount
       END, 0) <> 0
ORDER BY i.period DESC, i.id;
