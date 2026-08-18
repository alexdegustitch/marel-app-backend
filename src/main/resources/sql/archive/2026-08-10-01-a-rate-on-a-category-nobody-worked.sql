-- =============================================================================
-- The rate shown beside a category nobody worked
-- =============================================================================
-- THE DEFECT
-- When a payroll is recalculated, the loop over its categories has two branches.
-- The one for a category WITH activity writes the effective hourly rate. The one
-- for a category with NO activity zeroed the minutes, the norm, the amount and
-- the bonus — and left the rate alone. So the row kept whatever it was last
-- written with: 0 for a row created while the payroll had no rate yet, and an
-- old figure for a row that once had activity at a different one.
--
-- The payroll then read 500 at the top and 0 beside every category, which is
-- where somebody looks to check what the work is being priced at.
--
-- The code is fixed (both branches now ask one expression for the rate), but a
-- payroll only re-prices when it notices it is stale. An employee with no work
-- in a month has nothing to make it notice, so those rows would have stayed
-- wrong until somebody entered a work log — which is exactly how the defect was
-- reported.
--
-- WHAT THIS CHANGES
-- The displayed rate on category rows with NO ACTIVITY, on payrolls that are
-- still open. Nothing else.
--
-- NO MONEY MOVES. amount = (effective_minutes / 60) * hourly_rate, and
-- effective_minutes is zero on every row this touches, so every amount is zero
-- before and after. Verify with the SELECT at the bottom before committing.
--
-- WHAT IT DELIBERATELY LEAVES ALONE
--   * LOCKED payrolls. They are records of what was paid; a figure on one is not
--     corrected in place, whatever it says.
--   * Rows WITH activity. Their rate is the snapshot the amount was computed
--     from, and rewriting it would make the stored amount unexplainable.
--   * Categories with their own fixed rate. That rate is the category's, not the
--     employee's, and the payroll's rate never applied to it.
--
-- Each row takes ITS OWN payroll's rate, not today's rate for the employee, so
-- an old month keeps the price that was in force for it.
-- =============================================================================

BEGIN;

UPDATE payroll_run_item_categories pric
SET hourly_rate = pri.hourly_rate,
    updated_at  = now()
FROM payroll_run_items pri,
     work_code_categories wcc
WHERE pric.payroll_run_item_id = pri.id
  AND pric.work_code_category_id = wcc.id
  AND pri.status <> 'LOCKED'
  AND pri.archived_at IS NULL
  -- Only rows with nothing on them: no activity means no snapshot to preserve.
  AND COALESCE(pric.total_minutes, 0) = 0
  AND COALESCE(pric.effective_minutes, 0) = 0
  AND wcc.fixed_hourly_rate = FALSE
  AND pri.hourly_rate IS NOT NULL
  AND pric.hourly_rate IS DISTINCT FROM pri.hourly_rate;

-- Proof that no money moved: this must return no rows.
SELECT pric.id, pric.amount
FROM payroll_run_item_categories pric
WHERE COALESCE(pric.total_minutes, 0) = 0
  AND COALESCE(pric.amount, 0) <> 0;

COMMIT;
