-- =============================================================================
-- Per-day transport entitlement is backdated to 2025-01-01
-- =============================================================================
-- WHAT WAS WRONG
-- 2026-09-10-01 replaced the implicit "everybody without a fixed amount" mode
-- with a dated per-employee entitlement, and backfilled it from the first month
-- not yet calculated — 2026-09-01 in this database. That was the safe default
-- when nobody had said when transport actually started: it could not change a
-- month that had already been on a payslip.
--
-- The owner has now said it. Per-day transport has applied since 2025-01-01, so
-- every month from then on is entitled to it, and July and August 2026 paying
-- nothing was the backfill date showing through rather than a rule.
--
-- WHAT THIS DOES
--   1. Moves every entitlement the 2026-09-10-01 backfill created back to
--      2025-01-01, or to the employee's start date when that is later.
--   2. Creates one for employees the backfill skipped because they are archived.
--      That is what makes an employee who left in July 2026 paid for the days
--      they worked before leaving.
--
-- GREATEST(2025-01-01, employment_start_date) rather than a flat date: an
-- entitlement that predates the employment is not false so much as meaningless,
-- and it pays exactly the same, because there is no work to count before someone
-- was hired. Recording the truthful date costs nothing and reads correctly on the
-- employee's screen.
--
-- WHAT THIS DELIBERATELY DOES NOT TOUCH
--   * Employees on TRANSPORT_FIXED_MONTHLY. They are on the other mode, paid a
--     whole monthly amount; giving them a per-arrival entitlement as well would
--     be a second mode for the same thing. Same rule as 2026-09-10-01.
--   * Any TRANSPORT_PER_DAY row whose value is FALSE. A FALSE row is somebody
--     deciding this employee is NOT paid per-day transport from that date.
--     Extending a decision backwards is not a backfill. Employee 6 has one, from
--     2026-08-07, and it is left exactly as it is.
--   * Any row an administrator has already dated earlier than this would.
--
-- THE AMOUNT DOES NOT MOVE HERE. It is still the one company rate,
-- app_settings.transport_allowance_per_day. This says WHO and FROM WHEN.
--
-- RECALCULATION IS REQUIRED AND IT WILL CHANGE PAYSLIPS.
-- Nothing recalculates from a data change alone. Until the affected months are
-- recalculated their transport lines keep the figures they were last calculated
-- with, which are now wrong in both directions:
--   * 2026-07 and 2026-08 are mostly ZERO and will gain transport;
--   * some 2026-06 and 2026-08 lines were calculated BEFORE 2026-09-10-01, still
--     carry a per-shift figure, and will change — those with two consecutive
--     shifts in a day will go DOWN, because a changeover is no longer a second
--     fare (see the counting rule in DailyReportRepository.countQualifyingArrivals).
-- No payroll item in this database is LOCKED, so nothing is protected from this.
--
-- Re-runnable: both statements are guarded and converge on the same state.
-- =============================================================================

DO $$
DECLARE
    v_definition_id BIGINT;
    v_moved         INT;
    v_created       INT;
BEGIN
    SELECT id INTO v_definition_id
    FROM employee_payroll_value_definitions
    WHERE code = 'TRANSPORT_PER_DAY';

    IF v_definition_id IS NULL THEN
        RAISE EXCEPTION 'TRANSPORT_PER_DAY definition is missing — apply 2026-09-10-01 first.';
    END IF;

    -- 1. Move the backfilled entitlements back.
    --
    --    Only TRUE rows, and only where the new date is EARLIER than the one they
    --    carry, so a re-run is a no-op and an administrator who has already dated
    --    somebody from further back keeps their date.
    --
    --    Widening a single row's period backwards cannot collide with
    --    ex_epvh_no_overlap unless the same employee has an earlier period for the
    --    same definition; the NOT EXISTS guard leaves those alone rather than
    --    failing the script.
    WITH moved AS (
        UPDATE employee_payroll_value_history h
        SET valid_from = GREATEST(DATE '2025-01-01', e.employment_start_date),
            note = COALESCE(h.note || ' | ', '')
                   || 'Backdated to 2025-01-01 by 2026-09-13-01: per-day transport has applied since then.'
        FROM employees e
        WHERE e.id = h.employee_id
          AND h.value_definition_id = v_definition_id
          AND h.archived_at IS NULL
          AND h.boolean_value IS TRUE
          AND h.valid_from > GREATEST(DATE '2025-01-01', e.employment_start_date)
          AND NOT EXISTS (
              SELECT 1 FROM employee_payroll_value_history other
              WHERE other.employee_id = h.employee_id
                AND other.value_definition_id = v_definition_id
                AND other.archived_at IS NULL
                AND other.id <> h.id
                AND other.valid_from < h.valid_from)
        RETURNING 1)
    SELECT count(*) INTO v_moved FROM moved;

    -- 2. Create one for every employee who has none at all — archived included.
    --
    --    Archived employees are the point of this half: the backfill skipped them
    --    as "not current", but a month they actually worked is a month they
    --    actually travelled, and their payslip for it still has to be right.
    WITH created AS (
        INSERT INTO employee_payroll_value_history
            (employee_id, value_definition_id, value_type, boolean_value, valid_from, note)
        SELECT e.id, v_definition_id, 'BOOLEAN', TRUE,
               GREATEST(DATE '2025-01-01', e.employment_start_date),
               'Created by 2026-09-13-01: per-day transport has applied since 2025-01-01. '
               || 'The 2026-09-10-01 backfill covered only unarchived employees.'
        FROM employees e
        WHERE NOT EXISTS (
                  SELECT 1 FROM employee_payroll_value_history h
                  WHERE h.employee_id = e.id
                    AND h.value_definition_id = v_definition_id
                    AND h.archived_at IS NULL)
          AND NOT EXISTS (
                  SELECT 1 FROM employee_payroll_value_history f
                  JOIN employee_payroll_value_definitions fd ON fd.id = f.value_definition_id
                  WHERE f.employee_id = e.id
                    AND fd.code = 'TRANSPORT_FIXED_MONTHLY'
                    AND f.archived_at IS NULL)
        RETURNING 1)
    SELECT count(*) INTO v_created FROM created;

    RAISE NOTICE 'TRANSPORT_PER_DAY: % entitlements moved back, % created.', v_moved, v_created;
END $$;


-- =============================================================================
-- Diagnostics — run after applying, before recalculating
-- =============================================================================
-- Anybody still without either mode. Expected: only employees on
-- TRANSPORT_FIXED_MONTHLY, plus employee 6, whose FALSE row is a decision.
--
--   SELECT e.id, e.full_name, e.archived_at IS NOT NULL AS archived
--   FROM employees e
--   WHERE NOT EXISTS (
--       SELECT 1 FROM employee_payroll_value_history h
--       JOIN employee_payroll_value_definitions d ON d.id = h.value_definition_id
--       WHERE h.employee_id = e.id AND h.archived_at IS NULL
--         AND h.boolean_value IS NOT FALSE
--         AND d.code IN ('TRANSPORT_PER_DAY', 'TRANSPORT_FIXED_MONTHLY'));
--
-- The earliest entitlement per employee — expect 2025-01-01 or a start date:
--
--   SELECT h.valid_from, count(*)
--   FROM employee_payroll_value_history h
--   JOIN employee_payroll_value_definitions d ON d.id = h.value_definition_id
--   WHERE d.code = 'TRANSPORT_PER_DAY' AND h.archived_at IS NULL
--   GROUP BY 1 ORDER BY 1;
-- =============================================================================
