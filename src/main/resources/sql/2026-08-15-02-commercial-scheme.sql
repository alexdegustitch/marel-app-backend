-- =============================================================================
-- COMMERCIAL — the third compensation scheme
-- =============================================================================
-- Commercial staff are already a distinct payroll group: employees.works_in_commercial
-- has existed for a long time. But nothing in the calculation ever read it — the
-- flag drives a badge in the UI and nothing else — so "commercial employees get no
-- hourly bonus" has never actually been a rule the system applies.
--
-- This makes it one, as a scheme rather than as another boolean the calculators
-- would have to check. D4: work area, worker status and contract type stay
-- separate CONCEPTS, and a compensation scheme is the payroll POLICY for one
-- combination of them.
--
-- THE BONUS IS SHOWN, AND IT IS ZERO
-- Two mechanisms, both needed, doing different things:
--
--   compensation_schemes.allows_performance_bonus = FALSE
--       zeroes payroll_run_item_categories.bonus_amount, so the hourly bonus is
--       not earned in the first place. Efficiency is untouched: approved
--       performance already weighted the minutes that became the category amount.
--       A commercial employee is still paid more for working faster.
--
--   the MONTHLY_BONUS rule, calculation_mode = ZERO
--       keeps the LINE on the payslip at 0,00 and stops anyone entering a figure.
--       Without it the line would simply be blank, and "no bonus this month"
--       would look like "the bonus has not been entered yet".
--
-- OPEN-1, decided 2026-07-31: a commercial employee may NOT be given a bonus by
-- hand. allow_total_override = FALSE and editable_input = NONE. The UI must render
-- it read-only, not as a disabled input that looks temporarily unavailable.
--
-- BACKFILL DATE — D1
-- A scheme change takes effect on the first day of the FOLLOWING month, so nobody
-- ends up with two schemes inside one payroll month. Derived from the data rather
-- than hard-coded: the month after the latest payroll period that exists.
--
-- Re-runnable: guarded INSERTs throughout.
-- =============================================================================

INSERT INTO compensation_schemes (code, name, allow_unmapped_categories, allows_performance_bonus, note)
SELECT 'COMMERCIAL',
       'Komercijala',
       TRUE,
       FALSE,
       'Work categories behave as under STANDARD. No hourly bonus: allows_performance_bonus = false zeroes the category bonus, and the MONTHLY_BONUS rule keeps the line visible at zero.'
WHERE NOT EXISTS (SELECT 1 FROM compensation_schemes WHERE code = 'COMMERCIAL');


-- ── The MONTHLY_BONUS rule: visible, zero, not editable ─────────────────────
INSERT INTO payroll_adjustment_category_scheme_rules
    (compensation_scheme_id, payroll_adjustment_category_id,
     is_allowed, calculation_mode, visible_in_ui, visible_in_pdf, show_when_zero,
     editable_input, allow_total_override, required_manual_input,
     valid_from, note)
SELECT s.id, c.id,
       TRUE,      -- the line exists
       'ZERO',    -- but is never calculated
       TRUE, TRUE, TRUE,
       'NONE',    -- OPEN-1: no correction either
       FALSE,     -- OPEN-1: and no typed-in total
       FALSE,
       DATE '2020-01-01',
       'Komercijala nema bonus po satima. Linija ostaje na obračunu radi jasnoće, kao nula.'
FROM compensation_schemes s
JOIN payroll_adjustment_categories c ON c.code = 'MONTHLY_BONUS'
WHERE s.code = 'COMMERCIAL'
  AND NOT EXISTS (
      SELECT 1 FROM payroll_adjustment_category_scheme_rules r
      WHERE r.compensation_scheme_id = s.id
        AND r.payroll_adjustment_category_id = c.id
        AND r.archived_at IS NULL);


-- ── Move the current commercial staff onto it, from next month ──────────────
DO $$
DECLARE
    v_scheme_id  BIGINT;
    v_from       DATE;
    v_moved      INTEGER;
BEGIN
    SELECT id INTO v_scheme_id FROM compensation_schemes WHERE code = 'COMMERCIAL';
    IF v_scheme_id IS NULL THEN
        RAISE EXCEPTION 'COMMERCIAL scheme missing; the insert above did not run';
    END IF;

    -- D1: the first day of the month after the latest payroll period, so no
    -- existing payroll month can end up spanning two schemes.
    SELECT COALESCE(
               (SELECT (max(i.period) + INTERVAL '1 month')::date
                FROM payroll_run_items i
                WHERE i.archived_at IS NULL AND i.period IS NOT NULL),
               (date_trunc('month', now()) + INTERVAL '1 month')::date)
      INTO v_from;

    -- Close the open period the day before, then open the new one. Two statements,
    -- in this order, because the exclusion constraint would reject the pair the
    -- other way round.
    UPDATE employee_compensation_scheme_history h
    SET valid_until = v_from - 1
    FROM employees e
    WHERE e.id = h.employee_id
      AND e.works_in_commercial
      AND h.valid_until IS NULL
      AND h.archived_at IS NULL
      AND h.compensation_scheme_id <> v_scheme_id
      AND h.valid_from < v_from;

    INSERT INTO employee_compensation_scheme_history
        (employee_id, compensation_scheme_id, valid_from, valid_until, note)
    SELECT e.id, v_scheme_id, v_from, NULL,
           'Backfilled by 2026-08-15-02 from employees.works_in_commercial. Effective from the'
           || ' first day of the next payroll month so no month spans two schemes (D1).'
    FROM employees e
    WHERE e.works_in_commercial
      AND e.archived_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM employee_compensation_scheme_history h
          WHERE h.employee_id = e.id
            AND h.compensation_scheme_id = v_scheme_id
            AND h.archived_at IS NULL);
    GET DIAGNOSTICS v_moved = ROW_COUNT;

    RAISE NOTICE '% employee(s) moved to COMMERCIAL from %.', v_moved, v_from;
    RAISE NOTICE 'employees.works_in_commercial stays as personnel data, but it is no '
        'longer authoritative for payroll. No calculator reads it.';
END $$;
