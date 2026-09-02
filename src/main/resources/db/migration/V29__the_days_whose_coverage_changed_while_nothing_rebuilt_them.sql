-- =============================================================================
-- The days whose coverage changed while nothing rebuilt them
-- =============================================================================
-- WHAT THIS DOES
--   Queues a daily recalculation for every shift carrying a live absence. No
--   schema changes: it writes work items, exactly as V26 and V28 do.
--
-- WHY AGAIN, AFTER V26
--   V26 requeued these days so absences could become category rows at all. This
--   is a different fault on the same days: the allocation requeued a day only
--   when NO became ND or back, so an absence whose COVERAGE moved — from zero to
--   sixty minutes, say — had its verdict written and nothing rebuilt. The
--   payroll charges the uncovered part, so the category row kept charging hours
--   the overtime bank had already paid for.
--
--   Employee 38, 1 August 2026: two absences of an hour each, both covered whole
--   by the bank, and 120 minutes still standing against NO.
--
-- WHY THE FIX ALONE IS NOT ENOUGH
--   The code now requeues when the covered minutes move. But these already
--   moved: compensated_minutes is written, so the next allocation computes the
--   same number, sees nothing change, and correctly requeues nothing. The days
--   have to be told once, from here, that the answer beneath them shifted while
--   nobody was recomputing it.
--
-- WHY EVERY ABSENCE DAY AND NOT ONLY THE COVERED ONES
--   A row is stale when its minutes are not absence_minutes minus
--   compensated_minutes, and working that out in SQL means reproducing in a
--   migration the arithmetic the recalculation exists to perform. The set is
--   small, the recalculation is idempotent, and a day that was already right is
--   rebuilt to the same numbers.
--
-- ONE JOB PER EMPLOYEE-DAY, and the same NOT EXISTS as V26 and V28:
--   uq_daily_queue_pending allows a single PENDING row per (employee, work_date).
-- =============================================================================

INSERT INTO public.daily_report_recalc_queue
    (employee_id, work_shift_id, work_date, reason, status, requested_at, retry_count, version)
SELECT DISTINCT ON (ws.employee_id, ws.work_date)
       ws.employee_id,
       ws.id,
       ws.work_date,
       'V29_ABSENCE_COVERAGE_REBUILD',
       'PENDING',
       now(),
       0,
       1
FROM public.absence_records ar
JOIN public.work_shifts ws ON ws.id = ar.work_shift_id
WHERE ar.is_active = true
  AND ar.archived_at IS NULL
  AND ws.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM public.daily_report_recalc_queue q
      WHERE q.employee_id = ws.employee_id
        AND q.work_date = ws.work_date
        AND q.status = 'PENDING'
  )
ORDER BY ws.employee_id, ws.work_date, ws.id
ON CONFLICT (work_shift_id) DO UPDATE SET
    reason       = EXCLUDED.reason,
    requested_at = now(),
    last_error   = NULL,
    version      = public.daily_report_recalc_queue.version + 1,
    status       = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN 'IN_PROGRESS' ELSE 'PENDING' END,
    processed_at = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN public.daily_report_recalc_queue.processed_at ELSE NULL END,
    claimed_at   = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN public.daily_report_recalc_queue.claimed_at ELSE NULL END,
    claimed_by   = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN public.daily_report_recalc_queue.claimed_by ELSE NULL END;
