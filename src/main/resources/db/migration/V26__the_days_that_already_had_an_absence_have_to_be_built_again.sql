-- =============================================================================
-- The days that already had an absence have to be built again
-- =============================================================================
-- WHAT THIS DOES
--   Queues a daily recalculation for every shift that carries a live absence.
--   No schema changes: it writes work items, not structure.
--
-- WHY IT IS NEEDED AT ALL
--   Until now an absence reached the day's TOTALS but never became a row in
--   daily_report_categories — and that table is the first step of the only path
--   a category takes to a payslip:
--
--       daily_report_categories -> monthly_report_categories
--                               -> payroll_run_item_categories
--
--   So three hours missed showed up as a smaller total with no NO line saying
--   why. The code that builds those rows ships with this release, but it only
--   runs when a day is recalculated, and nothing recalculates a day on its own.
--   An absence recorded before this deploy would have stayed invisible in the
--   payroll for as long as nobody happened to touch that shift again.
--
-- WHY THE QUEUE AND NOT A DIRECT WRITE
--   The rows cannot be written in SQL. They carry the category resolved for the
--   employee and date, the coefficient in force, and the scheme that applies —
--   all of which live in DailyRecalcService. Asking it to run is the only way to
--   get them right, and the queue is how this application asks.
--
--   The workers pick these up on startup, after Flyway has finished. Each one
--   rebuilds its day, which enqueues the month, which reprices the payroll.
--
-- ONE JOB PER EMPLOYEE-DAY
--   uq_daily_queue_pending allows a single PENDING row per (employee, work_date)
--   — the queue's own rule, because a daily recalculation is about the day. The
--   DISTINCT ON honours it rather than working around it. An employee with
--   absences on TWO shifts of one day therefore gets one job here; the second
--   shift is rebuilt the next time anything touches it. Rare enough to accept,
--   and named here so nobody has to rediscover it.
--
-- SAFE TO RUN ON A DATABASE WITH NOTHING TO DO
--   Where no absence exists, this inserts nothing. Where a job is already
--   waiting for that employee-day, the NOT EXISTS leaves it alone. Where the
--   shift already has a queue row, ON CONFLICT re-requests it without disturbing
--   one a worker is holding.
-- =============================================================================

INSERT INTO public.daily_report_recalc_queue
    (employee_id, work_shift_id, work_date, reason, status, requested_at, retry_count, version)
SELECT DISTINCT ON (ws.employee_id, ws.work_date)
       ws.employee_id,
       ws.id,
       ws.work_date,
       'V26_ABSENCE_BECOMES_A_CATEGORY',
       'PENDING',
       now(),
       0,
       1
FROM public.absence_records ar
JOIN public.work_shifts ws ON ws.id = ar.work_shift_id
WHERE ar.is_active = true
  AND ar.archived_at IS NULL
  AND ws.archived_at IS NULL
  -- Another shift of the same day is already waiting; that job rebuilds the day.
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
    -- A job a worker is holding is left where it is. Flipping it back to PENDING
    -- underneath the worker is what produces the stuck rows this queue has a
    -- stuck_count column for.
    status       = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN 'IN_PROGRESS' ELSE 'PENDING' END,
    processed_at = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN public.daily_report_recalc_queue.processed_at ELSE NULL END,
    claimed_at   = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN public.daily_report_recalc_queue.claimed_at ELSE NULL END,
    claimed_by   = CASE WHEN public.daily_report_recalc_queue.status = 'IN_PROGRESS'
                        THEN public.daily_report_recalc_queue.claimed_by ELSE NULL END;
