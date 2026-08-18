-- =============================================================================
-- Per-day transport becomes a dated entitlement, per employee
-- =============================================================================
-- WHAT WAS WRONG (OPEN-15)
-- The fixed monthly mode reads a per-employee value with a valid_from, so its
-- start date is recorded and a month before it pays nothing. The per-day mode
-- read NOTHING about the employee — it applied to everyone without a fixed
-- amount, priced from one company setting. So there was no date to hold it back:
-- 98 of 135 active employees, and every month they ever worked, would be paid
-- per-day transport the next time that month was recalculated. 322 unarchived
-- items predate 2026 and not one payroll item in this database is LOCKED.
--
-- WHAT THIS DOES
-- TRANSPORT_PER_DAY, a BOOLEAN per-employee value with a valid_from. Having it
-- TRUE and in force is what puts an employee on the per-day mode — the same
-- sentence that already governs TRANSPORT_FIXED_MONTHLY, so there is still no
-- separate flag that can fall out of step with the mode. An employee with
-- neither value gets no transport, which is the point: the mode now has a start
-- date, and before it there is no entitlement rather than a silent one.
--
-- THE AMOUNT DOES NOT MOVE HERE. It is still the one company rate,
-- app_settings.transport_allowance_per_day, and it is read at the LAST DAY of the
-- payroll month (client's rule). This value says WHO and FROM WHEN, not HOW MUCH.
--
-- THE BACKFILL DATE IS DERIVED, NOT CHOSEN
-- Every entitlement starts on the first month NOT YET CALCULATED —
-- max(payroll_run_items.period) + 1 month — which is the same rule the Phase 2
-- rate backfill used, and for the same reason: no month that has already been on
-- a payslip can acquire a transport amount it did not have. In this database that
-- is 2026-09-01.
--
-- Where transport genuinely started earlier for somebody, that one employee is
-- backdated through EmployeePayrollValueService.changeValue, which accepts a date
-- before the whole history. Per employee, auditable, reversible by archiving the
-- row. That is the intended correction path, not a rerun of this file.
--
-- WHO GETS THE ENTITLEMENT: every unarchived employee who has no
-- TRANSPORT_FIXED_MONTHLY value in force — exactly the set the calculator treated
-- as per-day until now. Nobody gains or loses a mode; the mode gains a date.
--
-- Re-runnable.
-- =============================================================================

INSERT INTO employee_payroll_value_definitions
    (code, name, description, value_type, unit_code, payroll_adjustment_category_id, is_system)
SELECT 'TRANSPORT_PER_DAY',
       'Prevoz po danu',
       'TRUE means the employee is paid transport for each worked day, at the company '
       || 'rate app_settings.transport_allowance_per_day in force on the last day of the '
       || 'payroll month. Having this value in force is what puts an employee on the '
       || 'per-day mode, exactly as TRANSPORT_FIXED_MONTHLY does for the fixed mode. An '
       || 'employee with neither is paid no transport.',
       'BOOLEAN', NULL,
       (SELECT c.id FROM payroll_adjustment_categories c WHERE c.code = 'TRANSPORT_ALLOWANCE'),
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM employee_payroll_value_definitions
                  WHERE code = 'TRANSPORT_PER_DAY');

DO $$
DECLARE
    v_definition_id BIGINT;
    v_from          DATE;
    v_granted       INTEGER;
BEGIN
    SELECT id INTO v_definition_id
    FROM employee_payroll_value_definitions WHERE code = 'TRANSPORT_PER_DAY';

    -- The first month nobody has calculated yet. Derived from the data, so the
    -- date cannot be argued with and cannot quietly reach back into a payslip.
    SELECT COALESCE(max(period), CURRENT_DATE) + INTERVAL '1 month' INTO v_from
    FROM payroll_run_items WHERE archived_at IS NULL;
    v_from := date_trunc('month', v_from)::date;

    INSERT INTO employee_payroll_value_history
        (employee_id, value_definition_id, value_type, boolean_value, valid_from, note, created_at)
    SELECT e.id, v_definition_id, 'BOOLEAN', TRUE, v_from,
           'Backfill 2026-09-10-01: employee was on the per-day mode by absence of a '
           || 'fixed monthly amount. Started at the first uncalculated month so no '
           || 'existing payslip changes. Backdate through changeValue where the real '
           || 'start is earlier.',
           now()
    FROM employees e
    WHERE e.archived_at IS NULL
      -- not on the fixed mode, on the backfill date
      AND NOT EXISTS (
          SELECT 1 FROM employee_payroll_value_history h
          JOIN employee_payroll_value_definitions d ON d.id = h.value_definition_id
          WHERE h.employee_id = e.id
            AND d.code = 'TRANSPORT_FIXED_MONTHLY'
            AND h.archived_at IS NULL
            AND h.valid_from <= v_from
            AND (h.valid_until IS NULL OR h.valid_until >= v_from))
      -- and does not already have this entitlement — re-runnable
      AND NOT EXISTS (
          SELECT 1 FROM employee_payroll_value_history h
          WHERE h.employee_id = e.id
            AND h.value_definition_id = v_definition_id
            AND h.archived_at IS NULL);
    GET DIAGNOSTICS v_granted = ROW_COUNT;

    RAISE NOTICE 'TRANSPORT_PER_DAY granted to % employee(s) from %. Everyone else is on '
        'the fixed monthly mode or has no transport at all. The amount is unchanged — it '
        'is still the company rate, now read at the last day of the payroll month.',
        v_granted, v_from;
END $$;
