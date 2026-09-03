-- =============================================================================
-- A year of kartoni is read in one look
-- =============================================================================
-- WHAT CHANGES
--   Indexes only. No table, column or row is touched, and nothing here changes
--   what any query returns — only how long it takes to return it.
--
-- WHY
--   The Kartoni and Obračuni year views used to ask thirteen questions per year
--   (the activity feed, then "who touched this month" twelve times). They now
--   ask one each, and both questions are bounded by a date range:
--
--       employee_records.start_date >= 1 Jan AND < 1 Jan next year
--
--   employee_records had no index on start_date at all, so that range — and the
--   monthly list that has always filtered by the same column — was a scan of the
--   whole table. With a few thousand kartoni nobody notices; with millions the
--   year view reads every karton ever written to show twelve numbers.
--
--   The "what did I last have open" half reads the two activity trails by user.
--   Both trails are only indexed by (record, user), which is the right shape for
--   writing them and the wrong one for reading them back by user newest-first.
--
-- OPERATIONAL NOTE
--   Plain CREATE INDEX, which Flyway can run inside its transaction. On a table
--   that already holds millions of rows that blocks writes to it for the
--   duration of the build; if that matters for a deployment, build them ahead of
--   time with CREATE INDEX CONCURRENTLY under the same names — IF NOT EXISTS
--   makes this script a no-op for the ones that already exist.
-- =============================================================================

-- The year range, and the month range the monthly list has always used.
-- employee_id rides along so the distinct-employee count is answered from the
-- index; id so a walk in start_date order is deterministic.
CREATE INDEX IF NOT EXISTS idx_employee_records_start_date
    ON public.employee_records (start_date, employee_id, id);

-- "The kartoni I last had open", newest first.
CREATE INDEX IF NOT EXISTS idx_employee_record_updates_user_activity
    ON public.employee_record_updates (user_id, last_activity_at DESC);

-- "The obračuni I last had open", newest first.
CREATE INDEX IF NOT EXISTS idx_epriu_user_activity
    ON public.employee_payroll_run_item_updates (user_id, last_activity_at DESC);

-- A month's status breakdown without touching the row: run, then status,
-- over the items that still count.
CREATE INDEX IF NOT EXISTS idx_pri_run_status_live
    ON public.payroll_run_items (payroll_run_id, status)
    WHERE archived_at IS NULL;
