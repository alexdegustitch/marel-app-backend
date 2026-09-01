-- =============================================================================
-- The weekends a month opens on have to be decided again
-- =============================================================================
-- WHAT THIS DOES
--   Queues a daily recalculation for every Saturday and Sunday whose week began
--   in the PREVIOUS month. No schema changes: it writes work items.
--
-- WHY
--   The weekend bonus window used to start on the Monday of the week, which for
--   a weekend at the start of a month reaches back into the month before — so
--   one month's weekend was decided by another month's attendance. It now stops
--   at the first of the month, and August 2026, which opens on a Saturday, is
--   where that showed: five days of July counted against a Saturday nobody could
--   have prepared for.
--
--   The fix only takes effect when a day is recalculated, and nothing
--   recalculates a day on its own. V26 requeued the days carrying an ABSENCE,
--   which is a different set entirely — a Saturday the 1st that somebody worked
--   normally was not in it, and kept the answer it was given under the old rule.
--
-- WHICH DAYS EXACTLY
--   date_trunc('week') is Monday in Postgres, so "the week started before the
--   month did" is precisely the set whose window changed. Everything else was
--   already answered against days inside its own month and cannot have moved.
--
-- ONE JOB PER EMPLOYEE-DAY, and the same NOT EXISTS as V26: uq_daily_queue_pending
--   allows a single PENDING row per (employee, work_date), and the jobs V26 just
--   queued are still sitting in it when this runs.
-- =============================================================================

INSERT INTO public.daily_report_recalc_queue
    (employee_id, work_shift_id, work_date, reason, status, requested_at, retry_count, version)
SELECT DISTINCT ON (ws.employee_id, ws.work_date)
       ws.employee_id,
       ws.id,
       ws.work_date,
       'V28_WEEKEND_WINDOW_STOPS_AT_THE_MONTH',
       'PENDING',
       now(),
       0,
       1
FROM public.work_shifts ws
WHERE ws.archived_at IS NULL
  AND EXTRACT(isodow FROM ws.work_date) IN (6, 7)
  AND date_trunc('week', ws.work_date)::date < date_trunc('month', ws.work_date)::date
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
