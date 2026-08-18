-- =============================================================================
-- Per-arrival transport for the months BEFORE an employee's fixed amount began
-- =============================================================================
-- WHAT WAS WRONG
-- 2026-09-13-01 backdated the per-arrival entitlement to 2025-01-01 for everybody
-- who does NOT hold a TRANSPORT_FIXED_MONTHLY value. That exclusion — inherited
-- from 2026-09-10-01 — asks whether a fixed amount EXISTS, not when it STARTS.
--
-- Every fixed amount in this database starts 2026-08-01, and all 37 of those
-- employees were hired before that. So for every month up to July 2026 they held
-- neither value and were paid nothing:
--
--     2026-06   37 items   NO_TRANSPORT_ENTITLEMENT
--     2026-07   37 items   NO_TRANSPORT_ENTITLEMENT
--
-- The owner has confirmed they WERE reimbursed per arrival before their fixed
-- arrangement began.
--
-- WHAT THIS DOES
-- One closed per-arrival period per such employee:
--
--     GREATEST(2025-01-01, employment_start_date)  ..  fixed.valid_from - 1
--
-- CLOSED, NOT OPEN-ENDED, AND THAT IS THE WHOLE POINT. The two are modes, never
-- two rates for one thing. An open-ended row would overlap the fixed period and
-- be rejected by ex_epvh_no_overlap — which is the constraint doing its job:
-- "paid a fixed monthly amount AND paid per arrival" is not a state this system
-- is allowed to represent. Ending the day before the fixed amount starts is what
-- makes the handover exact.
--
-- MIN(valid_from) rather than the single row: today every employee has exactly
-- one fixed period, but the earliest one is what the per-arrival period has to
-- stop before, and writing it that way costs nothing.
--
-- WHO IS EXCLUDED
--   * Anyone who already has a TRANSPORT_PER_DAY row. 2026-09-13-01 gave one to
--     everyone it could, so a remaining row is either that backfill or a
--     deliberate decision, and neither is this script's business.
--   * Anyone whose fixed amount starts on or before their entitlement would —
--     there is no gap to fill, and a period whose end precedes its start is not
--     a row worth writing.
--
-- EMPLOYEE 6 IS NOT COVERED HERE. Her TRANSPORT_PER_DAY history is FALSE from
-- 2026-08-07 and TRUE from 2026-09-01, so she has no entitlement before
-- 2026-08-07 — but she holds no fixed amount, so this script does not see her,
-- and the FALSE row is a decision rather than a gap. Whether the months before it
-- should be paid is a separate question. See docs/business-rules/transport-allowance.md §7.
--
-- RECALCULATION IS REQUIRED. Run PayrollRecalculationRunner afterwards; nothing
-- recalculates from a data change alone.
--
-- Re-runnable: guarded on the absence of a TRANSPORT_PER_DAY row.
-- =============================================================================

DO $$
DECLARE
    v_definition_id BIGINT;
    v_created       INT;
BEGIN
    SELECT id INTO v_definition_id
    FROM employee_payroll_value_definitions
    WHERE code = 'TRANSPORT_PER_DAY';

    IF v_definition_id IS NULL THEN
        RAISE EXCEPTION 'TRANSPORT_PER_DAY definition is missing — apply 2026-09-10-01 first.';
    END IF;

    WITH fixed_start AS (
        SELECT h.employee_id, min(h.valid_from) AS starts_on
        FROM employee_payroll_value_history h
        JOIN employee_payroll_value_definitions d ON d.id = h.value_definition_id
        WHERE d.code = 'TRANSPORT_FIXED_MONTHLY'
          AND h.archived_at IS NULL
        GROUP BY h.employee_id
    ),
    created AS (
        INSERT INTO employee_payroll_value_history
            (employee_id, value_definition_id, value_type, boolean_value,
             valid_from, valid_until, note)
        SELECT f.employee_id, v_definition_id, 'BOOLEAN', TRUE,
               GREATEST(DATE '2025-01-01', e.employment_start_date),
               f.starts_on - 1,
               'Created by 2026-09-14-01: paid per arrival until the fixed monthly '
               || 'amount began on ' || f.starts_on || '.'
        FROM fixed_start f
        JOIN employees e ON e.id = f.employee_id
        WHERE f.starts_on > GREATEST(DATE '2025-01-01', e.employment_start_date)
          AND NOT EXISTS (
                  SELECT 1 FROM employee_payroll_value_history h
                  WHERE h.employee_id = f.employee_id
                    AND h.value_definition_id = v_definition_id
                    AND h.archived_at IS NULL)
        RETURNING 1)
    SELECT count(*) INTO v_created FROM created;

    RAISE NOTICE 'TRANSPORT_PER_DAY: % closed period(s) created before a fixed amount.', v_created;
END $$;


-- =============================================================================
-- Diagnostics — run after applying, before recalculating
-- =============================================================================
-- Nobody should hold both modes on the same date. Expect zero rows:
--
--   SELECT p.employee_id, p.valid_from, p.valid_until, f.valid_from AS fixed_from
--   FROM employee_payroll_value_history p
--   JOIN employee_payroll_value_definitions pd ON pd.id = p.value_definition_id AND pd.code = 'TRANSPORT_PER_DAY'
--   JOIN employee_payroll_value_history f ON f.employee_id = p.employee_id AND f.archived_at IS NULL
--   JOIN employee_payroll_value_definitions fd ON fd.id = f.value_definition_id AND fd.code = 'TRANSPORT_FIXED_MONTHLY'
--   WHERE p.archived_at IS NULL AND p.boolean_value IS TRUE
--     AND daterange(p.valid_from, COALESCE(p.valid_until + 1, NULL))
--      && daterange(f.valid_from, COALESCE(f.valid_until + 1, NULL));
-- =============================================================================
