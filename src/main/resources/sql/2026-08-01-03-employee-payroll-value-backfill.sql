-- =============================================================================
-- Backfill of employee_payroll_value_history
-- =============================================================================
-- WHY hourly_rate_system AND NOT hourly_rate  (diagnostic Q13c, 2026-07-31)
-- payroll_run_items carries both. `hourly_rate_system` is the employee's rate as
-- it was applied; `hourly_rate` is that rate AFTER a per-month override. Employee
-- 2 shows system 460 throughout, with hourly_rate 500 in 2026-01 and 2026-06 and
-- back to 460 in 2026-07, every one of them flagged hourly_rate_overridden.
--
-- Those 500s are two one-off decisions about two months. Migrating them into the
-- rate history would turn them into a permanent raise followed by a permanent
-- cut, and would then reprice every month in between. Overrides stay on the item,
-- which is where they belong and where phase 4 keeps them.
--
-- WHY THE RESULT IS DELIBERATELY SMALL
-- Only 26 of 949 payroll items carry a positive rate at all, across 6 employees;
-- 923 are calculated at rate 0 and stay that way. Zero is not a rate, so a zero
-- system rate produces no row: "no value in force" is the honest statement and it
-- reproduces exactly what happens today. A backfill that covered more employees
-- would be inventing data.
--
-- WHY employees.hourly_rate IS ALSO READ
-- It is TODAY's truth and the current calculation reads it directly. An employee
-- whose rate was set on the employee record but who has no payroll item yet would
-- otherwise get no history at all and start calculating at zero.
--
-- TRANSPORT: TWO MODES, AND ONLY ONE OF THEM IS A PER-EMPLOYEE VALUE
-- Some employees are paid a FIXED MONTHLY transport amount, whole, whatever they
-- worked. Everyone else is paid for the days they actually worked, at the single
-- company rate app_settings.transport_allowance_per_day.
--
-- So only the fixed-mode employees get a value here. employees.transport_allowance_mode
-- is what says which mode somebody is on — the column exists for exactly this and
-- has never been read by the calculation.
--
-- Because it was never read, it was also never maintained: 37 of 134 AUTO
-- employees carry an amount. Decided 2026-08-02 (OPEN-18): HAVING AN AMOUNT IS THE
-- FIXED MODE, so the mode is corrected from the amount before anything is
-- migrated. One 5,00 RSD row is cleared instead — it is not a monthly allowance
-- under any reading.
--
-- Values are migrated verbatim, including ones that look implausible: the data is
-- what it is, and a migration is not the place to reinterpret somebody's amount.
--
-- WHY THE FIXED AMOUNT DOES *NOT* START AT THE HIRE DATE
-- Transport is structurally 0 today (transport_allowance_days is never computed),
-- so backdating to the hire date would, the moment the calculator runs, pay
-- transport for every month back to 2020. The value therefore starts from the
-- first month NOT YET CALCULATED — derived from the data, not chosen. Where an
-- employee's fixed amount genuinely began earlier, an administrator backdates that
-- one employee through EmployeePayrollValueService.changeValue.
--
-- ⚠️ THIS CONTROLS THE FIXED MODE ONLY. The per-day mode reads no per-employee
-- value at all, so its start date cannot be controlled here: every employee not on
-- the fixed mode is paid for every month in which they worked, historical months
-- included. Locking closed months is the only control for that — see OPEN-15.
--
-- Legacy columns are NOT dropped. They stay a read-only mirror until phase 7.
-- Re-runnable: every INSERT is guarded by NOT EXISTS on (employee, definition, valid_from).
-- =============================================================================

DO $$
DECLARE
    v_hourly_rate_id    BIGINT;
    v_transport_rate_id BIGINT;
    v_hourly_rows       INTEGER;
    v_transport_rows    INTEGER;
    v_transport_start   DATE;
    v_mode_fixed        INTEGER;
    v_implausible       INTEGER;
    v_no_hourly         INTEGER;
BEGIN
    SELECT id INTO v_hourly_rate_id
      FROM employee_payroll_value_definitions WHERE code = 'HOURLY_RATE';
    SELECT id INTO v_transport_rate_id
      FROM employee_payroll_value_definitions WHERE code = 'TRANSPORT_FIXED_MONTHLY';

    IF v_hourly_rate_id IS NULL OR v_transport_rate_id IS NULL THEN
        RAISE EXCEPTION 'employee_payroll_value_definitions seeds missing; run 2026-08-01-01 first';
    END IF;

    -- ── HOURLY_RATE from the payroll items ────────────────────────────────
    -- Consecutive periods with the same rate collapse into one row. The row ends
    -- the day before the next distinct rate begins — INCLUSIVE valid_until — and
    -- the last one stays open.
    WITH rates AS (
        SELECT i.employee_id,
               i.period,
               i.hourly_rate_system AS rate
        FROM payroll_run_items i
        WHERE i.archived_at IS NULL
          AND i.period IS NOT NULL
          AND i.hourly_rate_system IS NOT NULL
          AND i.hourly_rate_system > 0
    ),
    marked AS (
        SELECT employee_id, period, rate,
               CASE
                   WHEN rate IS DISTINCT FROM
                        lag(rate) OVER (PARTITION BY employee_id ORDER BY period)
                   THEN 1 ELSE 0
               END AS starts_a_run
        FROM rates
    ),
    grouped AS (
        SELECT employee_id, period, rate,
               sum(starts_a_run) OVER (PARTITION BY employee_id ORDER BY period) AS run_no
        FROM marked
    ),
    runs AS (
        SELECT employee_id, rate, min(period) AS from_period
        FROM grouped
        GROUP BY employee_id, rate, run_no
    ),
    closed AS (
        SELECT employee_id,
               rate,
               from_period,
               lead(from_period) OVER (PARTITION BY employee_id ORDER BY from_period)
                   - INTERVAL '1 day' AS until_period
        FROM runs
    )
    INSERT INTO employee_payroll_value_history
        (employee_id, value_definition_id, value_type, numeric_value,
         valid_from, valid_until, note)
    SELECT c.employee_id,
           v_hourly_rate_id,
           'NUMERIC',
           c.rate,
           c.from_period,
           c.until_period::date,
           'Backfilled by 2026-08-01-03 from payroll_run_items.hourly_rate_system.'
           || ' History before the first payroll period is not known.'
    FROM closed c
    WHERE NOT EXISTS (
        SELECT 1 FROM employee_payroll_value_history h
        WHERE h.employee_id = c.employee_id
          AND h.value_definition_id = v_hourly_rate_id
          AND h.valid_from = c.from_period
    );
    GET DIAGNOSTICS v_hourly_rows = ROW_COUNT;

    -- ── HOURLY_RATE for employees with a rate on the record but no item ────
    -- Their rate is real and currently in use; without this they would start
    -- calculating at zero the moment the calculation reads the history.
    INSERT INTO employee_payroll_value_history
        (employee_id, value_definition_id, value_type, numeric_value,
         valid_from, valid_until, note)
    SELECT e.id,
           v_hourly_rate_id,
           'NUMERIC',
           e.hourly_rate,
           e.employment_start_date,
           NULL,
           'Backfilled by 2026-08-01-03 from employees.hourly_rate — no payroll item'
           || ' carried a system rate for this employee. The start date is the hire'
           || ' date because the real one is not recorded anywhere.'
    FROM employees e
    WHERE e.hourly_rate IS NOT NULL
      AND e.hourly_rate > 0
      AND NOT EXISTS (
          SELECT 1 FROM employee_payroll_value_history h
          WHERE h.employee_id = e.id
            AND h.value_definition_id = v_hourly_rate_id
      );

    -- ── Correct transport_allowance_mode first  (OPEN-18) ─────────────────
    -- 37 of 134 AUTO employees carry an amount, and one FIXED employee does. Now
    -- that transport_allowance_rsd is understood to be a MONTHLY figure, those
    -- amounts read as monthly nadoknade (2 000 – 6 000) that were entered while the
    -- mode was never set. Decided 2026-08-02: an amount IS the fixed mode.
    --
    -- Done here rather than in a migration of its own so that the correction and
    -- the backfill it enables cannot be applied out of order.
    UPDATE employees
    SET transport_allowance_mode = 'FIXED'
    WHERE archived_at IS NULL
      AND transport_allowance_rsd IS NOT NULL
      AND transport_allowance_rsd > 5.00
      AND coalesce(transport_allowance_mode, 'AUTO') <> 'FIXED';
    GET DIAGNOSTICS v_mode_fixed = ROW_COUNT;

    -- The 5,00 RSD row is not a monthly transport allowance under any reading.
    -- Cleared to NULL and put on AUTO, so the employee is paid per worked day like
    -- everybody else without a fixed amount. Matched on the value rather than on an
    -- id, and the count is asserted: a data correction must not quietly grow.
    SELECT count(*) INTO v_implausible
    FROM employees
    WHERE archived_at IS NULL AND transport_allowance_rsd = 5.00;

    IF v_implausible > 1 THEN
        RAISE EXCEPTION 'Expected one employee with a 5.00 transport amount, found %. '
            'Check Q14 before letting this migration clear them.', v_implausible;
    END IF;

    UPDATE employees
    SET transport_allowance_rsd = NULL,
        transport_allowance_mode = 'AUTO'
    WHERE archived_at IS NULL
      AND transport_allowance_rsd = 5.00;

    RAISE NOTICE '% employee(s) moved to FIXED transport mode; % implausible amount(s) cleared.',
        v_mode_fixed, v_implausible;

    -- ── TRANSPORT_FIXED_MONTHLY, for the fixed-mode employees only ────────
    -- transport_allowance_mode = FIXED is what says somebody is paid a fixed
    -- monthly amount. An AUTO employee is paid per worked day from the company
    -- rate, so their transport_allowance_rsd is not migrated: it would put them on
    -- the wrong mode entirely, not merely at the wrong price.
    SELECT COALESCE(
               (SELECT (max(i.period) + INTERVAL '1 month')::date
                FROM payroll_run_items i
                WHERE i.archived_at IS NULL AND i.period IS NOT NULL),
               date_trunc('month', now())::date)
      INTO v_transport_start;

    INSERT INTO employee_payroll_value_history
        (employee_id, value_definition_id, value_type, numeric_value,
         valid_from, valid_until, note)
    SELECT e.id,
           v_transport_rate_id,
           'NUMERIC',
           e.transport_allowance_rsd,
           GREATEST(e.employment_start_date, v_transport_start),
           NULL,
           'Backfilled by 2026-08-01-03 from employees.transport_allowance_rsd where'
           || ' transport_allowance_mode = FIXED. The start date is the first month not'
           || ' yet calculated, NOT the real one, which is not recorded anywhere —'
           || ' backdate this employee if their fixed amount genuinely started earlier.'
    FROM employees e
    WHERE e.transport_allowance_mode = 'FIXED'
      AND e.transport_allowance_rsd IS NOT NULL
      AND e.transport_allowance_rsd > 0
      AND NOT EXISTS (
          SELECT 1 FROM employee_payroll_value_history h
          WHERE h.employee_id = e.id
            AND h.value_definition_id = v_transport_rate_id
      );
    GET DIAGNOSTICS v_transport_rows = ROW_COUNT;

    -- ── Report, so an under-backfill cannot pass unnoticed ────────────────
    SELECT count(*) INTO v_no_hourly
    FROM employees e
    WHERE e.archived_at IS NULL
      AND e.is_active
      AND NOT EXISTS (
          SELECT 1 FROM employee_payroll_value_history h
          WHERE h.employee_id = e.id AND h.value_definition_id = v_hourly_rate_id);

    RAISE NOTICE 'Backfill complete: % HOURLY_RATE period(s), % TRANSPORT_FIXED_MONTHLY row(s) from %.',
        v_hourly_rows, v_transport_rows, v_transport_start;
    RAISE NOTICE 'TRANSPORT_FIXED_MONTHLY starts at % so nothing already calculated gains '
        'an amount. Only FIXED-mode employees are migrated; everyone else is paid per '
        'worked day from app_settings.transport_allowance_per_day, which this cannot '
        'date-limit — see OPEN-15.', v_transport_start;
    RAISE NOTICE '% active employee(s) have NO hourly rate. That is expected: they are '
        'calculated at rate 0 today and continue to be. It is not a failed backfill.',
        v_no_hourly;
END $$;


-- =============================================================================
-- Verification — the backfill must reproduce what the items already say
-- =============================================================================
-- Every payroll item with a positive system rate must resolve, at its own period,
-- to exactly that rate. If this raises, the collapse above lost a period boundary
-- and recalculating that month would change somebody's pay.
DO $$
DECLARE
    v_mismatches INTEGER;
BEGIN
    SELECT count(*) INTO v_mismatches
    FROM payroll_run_items i
    JOIN employee_payroll_value_definitions d ON d.code = 'HOURLY_RATE'
    LEFT JOIN employee_payroll_value_history h
           ON h.employee_id = i.employee_id
          AND h.value_definition_id = d.id
          AND h.archived_at IS NULL
          AND h.valid_from <= i.period
          AND (h.valid_until IS NULL OR h.valid_until >= i.period)
    WHERE i.archived_at IS NULL
      AND i.period IS NOT NULL
      AND i.hourly_rate_system IS NOT NULL
      AND i.hourly_rate_system > 0
      AND (h.numeric_value IS NULL OR h.numeric_value <> i.hourly_rate_system);

    IF v_mismatches > 0 THEN
        RAISE EXCEPTION 'Backfill verification failed: % payroll item(s) do not resolve '
            'to their own system rate. The migration has been rolled back.', v_mismatches;
    END IF;

    RAISE NOTICE 'Verification passed: every payroll item resolves to its own system rate.';
END $$;
