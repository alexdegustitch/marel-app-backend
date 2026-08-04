-- =============================================================================
-- Payroll component migration — PHASE 0 DIAGNOSTICS
-- =============================================================================
-- READ ONLY. Every statement here is a SELECT. Nothing is inserted, updated or
-- deleted. Run this against the REAL database before any migration in
-- docs/business-rules/payroll-component-migration-plan.md is written.
--
-- HOW TO RUN
--   psql -d marel -v period_start="'2026-07-01'" -v period_end="'2026-07-31'" \
--        -f docs/business-rules/payroll-migration-diagnostics.sql
--
-- Without -v the two variables default to the current month (see D0 below).
--
-- WHY THIS EXISTS
-- Four decisions in the plan cannot be implemented until the data answers a
-- question about itself:
--
--   D9  a UNIQUE (payroll_run_item_id, payroll_adjustment_category_id) can only
--       be added if no duplicates exist. Duplicates must NOT be auto-merged.
--   D1  "exactly one scheme per payroll month" only ships once every employee
--       actually has exactly one.
--   D6  "a missing scheme x category rule is a configuration error" needs the
--       size of the current gap before the backfill is written.
--   R2  meal/transport stop being added directly to the total only after the
--       legacy columns and the adjustment rows are proven identical.
--
-- Each query prints a section header and either rows (= work to do) or nothing
-- (= clear). "0 rows" is the passing result for every query except D0 and Q5.
-- =============================================================================

\set ON_ERROR_STOP on
\timing off
\pset pager off

-- Default the period to the current month when not supplied on the command line.
SELECT COALESCE(:'period_start', date_trunc('month', now())::date::text)   AS period_start \gset
SELECT COALESCE(:'period_end',
                (date_trunc('month', now()) + interval '1 month - 1 day')::date::text) AS period_end \gset


\echo ''
\echo '============================================================'
\echo 'D0. Scope of this report'
\echo '============================================================'

SELECT :'period_start'::date AS period_start,
       :'period_end'::date   AS period_end,
       (SELECT count(*) FROM employees WHERE archived_at IS NULL AND is_active) AS active_employees,
       (SELECT count(*) FROM compensation_schemes WHERE archived_at IS NULL AND is_active) AS active_schemes,
       (SELECT count(*) FROM payroll_adjustment_categories WHERE archived_at IS NULL AND is_active) AS active_categories,
       (SELECT count(*) FROM payroll_run_items) AS payroll_run_items,
       (SELECT count(*) FROM payroll_adjustments) AS payroll_adjustments;


\echo ''
\echo '============================================================'
\echo 'Q1. Duplicate adjustments  (D9 — blocks the UNIQUE constraint)'
\echo '    Expected: 0 rows. Do NOT merge or delete automatically.'
\echo '============================================================'

SELECT a.payroll_run_item_id,
       c.code                        AS category_code,
       count(*)                      AS row_count,
       sum(a.amount)                 AS summed_amount,
       array_agg(a.id ORDER BY a.id) AS adjustment_ids,
       array_agg(a.amount ORDER BY a.id) AS amounts,
       array_agg(a.is_applied ORDER BY a.id) AS applied_flags,
       array_agg(coalesce(a.note, '-') ORDER BY a.id) AS notes,
       min(a.created_at)             AS first_created_at,
       max(a.created_at)             AS last_created_at
FROM payroll_adjustments a
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
GROUP BY a.payroll_run_item_id, c.code
HAVING count(*) > 1
ORDER BY count(*) DESC, a.payroll_run_item_id;


\echo ''
\echo 'Q1b. How each duplicate arose — created_by and creation gap'
\echo '     A gap of seconds suggests a double POST; days suggest deliberate use.'

WITH dups AS (
    SELECT payroll_run_item_id, payroll_adjustment_category_id
    FROM payroll_adjustments
    GROUP BY 1, 2
    HAVING count(*) > 1
)
SELECT a.payroll_run_item_id,
       c.code AS category_code,
       a.id,
       a.amount,
       a.is_applied,
       a.created_by,
       a.created_at,
       a.created_at - lag(a.created_at) OVER (
           PARTITION BY a.payroll_run_item_id, a.payroll_adjustment_category_id
           ORDER BY a.created_at) AS gap_from_previous
FROM payroll_adjustments a
JOIN dups d
  ON d.payroll_run_item_id = a.payroll_run_item_id
 AND d.payroll_adjustment_category_id = a.payroll_adjustment_category_id
JOIN payroll_adjustment_categories c ON c.id = a.payroll_adjustment_category_id
ORDER BY a.payroll_run_item_id, c.code, a.created_at;


\echo ''
\echo '============================================================'
\echo 'Q2. Overlapping compensation scheme assignments  (D1)'
\echo '    Expected: 0 rows — ex_ecsh_no_overlap should already prevent this.'
\echo '    A row here means the constraint is missing or was bypassed.'
\echo '============================================================'

SELECT a.employee_id,
       e.full_name,
       a.id AS period_a, sa.code AS scheme_a, a.valid_from AS from_a, a.valid_until AS until_a,
       b.id AS period_b, sb.code AS scheme_b, b.valid_from AS from_b, b.valid_until AS until_b
FROM employee_compensation_scheme_history a
JOIN employee_compensation_scheme_history b
  ON b.employee_id = a.employee_id
 AND b.id > a.id
 AND b.archived_at IS NULL
JOIN employees e  ON e.id = a.employee_id
JOIN compensation_schemes sa ON sa.id = a.compensation_scheme_id
JOIN compensation_schemes sb ON sb.id = b.compensation_scheme_id
WHERE a.archived_at IS NULL
  AND daterange(a.valid_from, CASE WHEN a.valid_until IS NULL THEN NULL ELSE a.valid_until + 1 END)
   && daterange(b.valid_from, CASE WHEN b.valid_until IS NULL THEN NULL ELSE b.valid_until + 1 END)
ORDER BY a.employee_id;


\echo ''
\echo '============================================================'
\echo 'Q3. Scheme periods that do not start on the 1st  (D1)'
\echo '    Every row is an employee whose scheme changed mid-month under the'
\echo '    old rules. They are legal history and are NOT rewritten — this'
\echo '    counts how many payroll months would resolve to two schemes.'
\echo '============================================================'

SELECT h.employee_id,
       e.full_name,
       s.code AS scheme_code,
       h.valid_from,
       h.valid_until,
       h.note
FROM employee_compensation_scheme_history h
JOIN employees e ON e.id = h.employee_id
JOIN compensation_schemes s ON s.id = h.compensation_scheme_id
WHERE h.archived_at IS NULL
  AND h.valid_from <> date_trunc('month', h.valid_from)::date
ORDER BY h.valid_from DESC, h.employee_id;


\echo ''
\echo 'Q3b. Payroll months that would resolve to MORE THAN ONE scheme  (D1 -> error)'
\echo '     Every row here becomes a hard error once phase 5 ships.'

SELECT h.employee_id,
       e.full_name,
       date_trunc('month', gs.month)::date AS payroll_month,
       count(*)                            AS scheme_count,
       array_agg(DISTINCT s.code)          AS schemes
FROM generate_series(
        (SELECT date_trunc('month', min(valid_from)) FROM employee_compensation_scheme_history WHERE archived_at IS NULL),
        date_trunc('month', now()) + interval '2 months',
        interval '1 month') AS gs(month)
JOIN employee_compensation_scheme_history h
  ON h.archived_at IS NULL
 AND daterange(h.valid_from, CASE WHEN h.valid_until IS NULL THEN NULL ELSE h.valid_until + 1 END)
  && daterange(gs.month::date, (gs.month + interval '1 month')::date)
JOIN employees e ON e.id = h.employee_id
JOIN compensation_schemes s ON s.id = h.compensation_scheme_id
GROUP BY h.employee_id, e.full_name, date_trunc('month', gs.month)
HAVING count(DISTINCT s.code) > 1
ORDER BY payroll_month DESC, h.employee_id;


\echo ''
\echo '============================================================'
\echo 'Q4. Employees with NO scheme covering the reported period  (D1 -> error)'
\echo '    Today these resolve to "unrestricted". After phase 5 they are errors,'
\echo '    so every row must be given a period before that ships.'
\echo '============================================================'

SELECT e.id AS employee_id,
       e.full_name,
       e.employee_no,
       e.employment_start_date,
       e.employment_end_date,
       e.is_foreigner,
       e.works_in_commercial
FROM employees e
WHERE e.archived_at IS NULL
  AND e.is_active
  AND (e.employment_end_date IS NULL OR e.employment_end_date >= :'period_start'::date)
  AND e.employment_start_date <= :'period_end'::date
  AND NOT EXISTS (
        SELECT 1
        FROM employee_compensation_scheme_history h
        WHERE h.employee_id = e.id
          AND h.archived_at IS NULL
          AND daterange(h.valid_from,
                        CASE WHEN h.valid_until IS NULL THEN NULL ELSE h.valid_until + 1 END)
           && daterange(:'period_start'::date, (:'period_end'::date + 1)))
ORDER BY e.full_name;


\echo ''
\echo '============================================================'
\echo 'Q5. Existing per-employee rate data  (D2 backfill sizing)'
\echo '    Rows expected. This is the input to employee_payroll_value_history.'
\echo '============================================================'

SELECT count(*)                                                         AS employees,
       count(hourly_rate)                                               AS with_hourly_rate,
       count(*) FILTER (WHERE hourly_rate IS NULL)                      AS missing_hourly_rate,
       count(transport_allowance_rsd)                                   AS with_transport_rate,
       count(*) FILTER (WHERE transport_allowance_rsd IS NOT NULL
                          AND transport_allowance_rsd > 0)              AS transport_rate_positive,
       count(*) FILTER (WHERE transport_allowance_mode = 'AUTO')        AS mode_auto,
       count(*) FILTER (WHERE transport_allowance_mode = 'FIXED')       AS mode_fixed,
       count(*) FILTER (WHERE transport_allowance_mode IS NULL)         AS mode_null,
       min(employment_start_date)                                       AS earliest_hire_date
FROM employees
WHERE archived_at IS NULL AND is_active;


\echo ''
\echo 'Q5b. Transport mode values actually present (the CHECK allows any string)'

SELECT coalesce(transport_allowance_mode, '<null>') AS transport_allowance_mode,
       count(*)                                     AS employees,
       count(transport_allowance_rsd)               AS with_amount,
       min(transport_allowance_rsd)                 AS min_amount,
       max(transport_allowance_rsd)                 AS max_amount
FROM employees
WHERE archived_at IS NULL AND is_active
GROUP BY 1
ORDER BY 2 DESC;


\echo ''
\echo '============================================================'
\echo 'Q6. Legacy payroll columns vs adjustment rows  (R2 — blocks phase 4)'
\echo '    Expected: 0 rows before the single-source-of-truth switch.'
\echo '============================================================'

SELECT i.id AS payroll_run_item_id,
       i.period,
       e.full_name,
       'MEAL_ALLOWANCE'                AS line,
       i.total_meal_allowance_amount   AS item_column,
       a.amount                        AS adjustment_row,
       i.total_meal_allowance_amount - coalesce(a.amount, 0) AS difference,
       a.is_applied
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
LEFT JOIN payroll_adjustments a
       ON a.payroll_run_item_id = i.id
      AND a.payroll_adjustment_category_id =
          (SELECT id FROM payroll_adjustment_categories WHERE code = 'MEAL_ALLOWANCE')
WHERE i.archived_at IS NULL
  AND coalesce(i.total_meal_allowance_amount, 0) <> coalesce(a.amount, 0)

UNION ALL

SELECT i.id,
       i.period,
       e.full_name,
       'TRANSPORT_ALLOWANCE',
       i.total_transport_allowance_amount,
       a.amount,
       i.total_transport_allowance_amount - coalesce(a.amount, 0),
       a.is_applied
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
LEFT JOIN payroll_adjustments a
       ON a.payroll_run_item_id = i.id
      AND a.payroll_adjustment_category_id =
          (SELECT id FROM payroll_adjustment_categories WHERE code = 'TRANSPORT_ALLOWANCE')
WHERE i.archived_at IS NULL
  AND coalesce(i.total_transport_allowance_amount, 0) <> coalesce(a.amount, 0)

UNION ALL

SELECT i.id,
       i.period,
       e.full_name,
       'MONTHLY_BONUS',
       i.total_bonus_amount,
       a.amount,
       i.total_bonus_amount - coalesce(a.amount, 0),
       a.is_applied
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
LEFT JOIN payroll_adjustments a
       ON a.payroll_run_item_id = i.id
      AND a.payroll_adjustment_category_id =
          (SELECT id FROM payroll_adjustment_categories WHERE code = 'MONTHLY_BONUS')
WHERE i.archived_at IS NULL
  AND coalesce(i.total_bonus_amount, 0) <> coalesce(a.amount, 0)

ORDER BY 2 DESC NULLS LAST, 1, 4;


\echo ''
\echo 'Q6b. Bonus components that do not add up (base + correction <> total)'
\echo '     Legitimate when total_bonus_amount_overridden is true.'

SELECT i.id AS payroll_run_item_id,
       i.period,
       i.base_bonus_amount,
       i.bonus_correction_amount,
       i.total_bonus_amount,
       i.base_bonus_amount + i.bonus_correction_amount - i.total_bonus_amount AS difference,
       i.total_bonus_amount_overridden
FROM payroll_run_items i
WHERE i.archived_at IS NULL
  AND i.base_bonus_amount + i.bonus_correction_amount <> i.total_bonus_amount
ORDER BY i.period DESC NULLS LAST, i.id;


\echo ''
\echo '============================================================'
\echo 'Q7. Unknown / unused calculation keys  (D6 — hard error later)'
\echo '    Compare the left column against PayrollCalculatorRegistry.'
\echo '============================================================'

SELECT coalesce(calculation_key, '<null — manual line>') AS calculation_key,
       count(*)                                          AS categories,
       array_agg(code ORDER BY code)                     AS category_codes,
       bool_or(is_active)                                AS any_active
FROM payroll_adjustment_categories
WHERE archived_at IS NULL
GROUP BY 1
ORDER BY 1;


\echo ''
\echo '============================================================'
\echo 'Q8. Scheme x category configuration gap  (D6)'
\echo '    Every row is a pair that must get an explicit rule in the phase 5'
\echo '    backfill. Today it silently means ALLOW.'
\echo '============================================================'

SELECT s.code AS scheme_code,
       c.code AS category_code,
       c.section_code,
       c.impact_code,
       c.is_manual,
       c.calculation_key
FROM compensation_schemes s
CROSS JOIN payroll_adjustment_categories c
WHERE s.archived_at IS NULL AND s.is_active
  AND c.archived_at IS NULL AND c.is_active
  AND NOT EXISTS (
        SELECT 1
        FROM payroll_adjustment_category_scheme_rules r
        WHERE r.compensation_scheme_id = s.id
          AND r.payroll_adjustment_category_id = c.id
          AND r.archived_at IS NULL
          AND r.is_active)
ORDER BY s.code, c.section_order, c.sort_order, c.code;


\echo ''
\echo 'Q8b. Configuration matrix summary'

SELECT s.code AS scheme_code,
       count(*) FILTER (WHERE r.id IS NOT NULL)                          AS rules_present,
       count(*) FILTER (WHERE r.id IS NULL)                              AS rules_missing,
       count(*) FILTER (WHERE r.is_allowed IS FALSE)                     AS explicit_denies,
       count(*)                                                          AS active_categories
FROM compensation_schemes s
CROSS JOIN payroll_adjustment_categories c
LEFT JOIN payroll_adjustment_category_scheme_rules r
       ON r.compensation_scheme_id = s.id
      AND r.payroll_adjustment_category_id = c.id
      AND r.archived_at IS NULL
      AND r.is_active
WHERE s.archived_at IS NULL AND s.is_active
  AND c.archived_at IS NULL AND c.is_active
GROUP BY s.code
ORDER BY s.code;


\echo ''
\echo '============================================================'
\echo 'Q9. Transport quantity the new rule WOULD produce  (D3)'
\echo '    Today transport_allowance_days is always 0, so system transport is'
\echo '    always 0. This is the money the phase 3 calculator starts paying.'
\echo '    Requires business sign-off before deploying phase 3 (risk R1).'
\echo '============================================================'

SELECT e.id AS employee_id,
       e.full_name,
       count(*) FILTER (WHERE dr.total_work_minutes > 0) AS qualifying_shifts,
       count(*)                                          AS all_shifts,
       e.transport_allowance_rsd                         AS rate,
       count(*) FILTER (WHERE dr.total_work_minutes > 0)
           * coalesce(e.transport_allowance_rsd, 0)      AS would_pay,
       (SELECT i.total_transport_allowance_amount
        FROM payroll_run_items i
        WHERE i.employee_id = e.id
          AND i.period = date_trunc('month', :'period_start'::date)::date
        LIMIT 1)                                         AS pays_today
FROM employees e
JOIN daily_reports dr
  ON dr.employee_id = e.id
 AND dr.work_date BETWEEN :'period_start'::date AND :'period_end'::date
 AND dr.archived_at IS NULL
WHERE e.archived_at IS NULL AND e.is_active
GROUP BY e.id, e.full_name, e.transport_allowance_rsd
ORDER BY qualifying_shifts DESC, e.full_name;


\echo ''
\echo 'Q9b. Shifts excluded by the "work_minutes > 0" rule — sanity check'
\echo '     These are shift records with no WORK minutes (absence, sick leave,'
\echo '     or an empty shift). They must NOT earn transport.'

SELECT dr.work_date,
       e.full_name,
       dr.total_shift_minutes,
       dr.total_work_minutes,
       dr.total_absence_paid_minutes + dr.total_absence_unpaid_minutes AS absence_minutes,
       dr.total_sick_leave_paid_minutes + dr.total_sick_leave_unpaid_minutes AS sick_minutes
FROM daily_reports dr
JOIN employees e ON e.id = dr.employee_id
WHERE dr.work_date BETWEEN :'period_start'::date AND :'period_end'::date
  AND dr.archived_at IS NULL
  AND coalesce(dr.total_work_minutes, 0) = 0
ORDER BY dr.work_date, e.full_name;


\echo ''
\echo '============================================================'
\echo 'Q10. Audit coverage for the tables the plan relies on  (D7)'
\echo '     payroll_adjustments MUST be present, or override history cannot be'
\echo '     reconstructed and D7 does not hold.'
\echo '============================================================'

SELECT t.table_name,
       (SELECT count(*) FROM audit_logs l WHERE l.table_id = t.id) AS audit_rows,
       EXISTS (SELECT 1 FROM pg_trigger g
               JOIN pg_class      cl ON cl.oid = g.tgrelid
               WHERE cl.relname = t.table_name
                 AND g.tgname = 'trg_audit_logs_' || t.table_name) AS trigger_present
FROM audit_tables t
WHERE t.table_name IN ('payroll_adjustments',
                       'payroll_adjustment_categories',
                       'payroll_adjustment_category_scheme_rules',
                       'payroll_run_items',
                       'compensation_schemes',
                       'employee_compensation_scheme_history',
                       'employees')
ORDER BY t.table_name;


\echo ''
\echo 'Q10b. Audited tables the plan touches that are MISSING from audit_tables'

SELECT v.table_name AS missing_from_audit_tables
FROM (VALUES ('payroll_adjustments'),
             ('payroll_adjustment_categories'),
             ('payroll_adjustment_category_scheme_rules'),
             ('payroll_run_items')) AS v(table_name)
WHERE NOT EXISTS (SELECT 1 FROM audit_tables t WHERE t.table_name = v.table_name);


\echo ''
\echo '============================================================'
\echo 'Q11. Locked payroll items  (R5 / D1 — must never be recalculated)'
\echo '============================================================'

SELECT status,
       count(*)          AS items,
       min(period)       AS earliest_period,
       max(period)       AS latest_period,
       count(*) FILTER (WHERE locked_at IS NOT NULL) AS with_locked_at
FROM payroll_run_items
WHERE archived_at IS NULL
GROUP BY status
ORDER BY status;


\echo ''
\echo '============================================================'
\echo 'Q12. The FULL adjustment catalogue  (mirrors PayrollScenarioFixture)'
\echo '     The 2026-07-31 run showed the catalogue has drifted from the'
\echo '     2026-04-25 seed: five categories live in sections of their own and'
\echo '     PAID_PART_2 changed impact. section_code decides which TOTAL a line'
\echo '     reaches, so PayrollScenarioFixture.CATALOGUE must match this exactly.'
\echo '============================================================'

SELECT code,
       name,
       section_code,
       section_order,
       sort_order,
       impact_code,
       input_type,
       is_manual,
       allow_override,
       override_target,
       allow_negative,
       visible_in_ui,
       visible_in_pdf,
       show_name,
       calculation_key,
       is_active
FROM payroll_adjustment_categories
WHERE archived_at IS NULL
ORDER BY section_order, sort_order, code;


\echo ''
\echo 'Q12b. Which section reaches which total, under TODAY''s arithmetic'
\echo '      recalculateSummaryTotals filters on the literal strings ADDITIONS'
\echo '      and SETTLEMENTS. Anything else contributes to no balance at all.'

SELECT section_code,
       count(*)                    AS categories,
       array_agg(code ORDER BY code) AS codes,
       CASE section_code
           WHEN 'ADDITIONS'   THEN 'additionsSum -> totalNetEarnings'
           WHEN 'SETTLEMENTS' THEN 'previouslyPaid -> currentBalance'
           ELSE                    'NO TOTAL — display only'
       END                         AS reaches
FROM payroll_adjustment_categories
WHERE archived_at IS NULL AND is_active
GROUP BY section_code
ORDER BY min(section_order), section_code;


\echo ''
\echo '============================================================'
\echo 'Q13. Where does the hourly rate ACTUALLY come from?  (D2 backfill)'
\echo '     The 2026-07-31 run found only 2 of 135 employees carry'
\echo '     employees.hourly_rate, so a backfill from that column would cover'
\echo '     almost nobody. If the rate really lives on the payroll item, the'
\echo '     phase 2 backfill has to read it from there instead.'
\echo '============================================================'

SELECT count(*)                                                              AS items,
       count(*) FILTER (WHERE i.hourly_rate > 0)                             AS item_rate_positive,
       count(*) FILTER (WHERE i.hourly_rate_overridden)                      AS item_rate_overridden,
       count(*) FILTER (WHERE e.hourly_rate IS NOT NULL)                     AS employee_rate_present,
       count(*) FILTER (WHERE i.hourly_rate > 0 AND e.hourly_rate IS NULL)   AS rate_only_on_the_item,
       count(*) FILTER (WHERE i.hourly_rate = 0 AND i.total_net_earnings > 0) AS paid_with_zero_rate
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
WHERE i.archived_at IS NULL;


\echo ''
\echo 'Q13b. Employees whose item rate CHANGED over time'
\echo '      Each distinct value is a period the phase 2 history must reproduce.'
\echo '      One value = one open-ended row is enough; several = real history.'

SELECT i.employee_id,
       e.full_name,
       count(DISTINCT i.hourly_rate)          AS distinct_rates,
       min(i.period)                          AS first_period,
       max(i.period)                          AS last_period,
       array_agg(DISTINCT i.hourly_rate)      AS rates
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
WHERE i.archived_at IS NULL
  AND i.hourly_rate > 0
GROUP BY i.employee_id, e.full_name
HAVING count(DISTINCT i.hourly_rate) > 1
ORDER BY count(DISTINCT i.hourly_rate) DESC, i.employee_id;


\echo ''
\echo 'Q13c. The rate per employee per period — the raw input to the backfill'

SELECT i.employee_id,
       e.full_name,
       i.period,
       i.hourly_rate,
       i.hourly_rate_system,
       i.hourly_rate_overridden
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
WHERE i.archived_at IS NULL
  AND i.hourly_rate > 0
ORDER BY i.employee_id, i.period;


\echo ''
\echo '============================================================'
\echo 'Q14. Transport rate data quality  (D2/D3)'
\echo '     The 2026-07-31 run showed rates from 5.00 to 6000.00 RSD. A rate of'
\echo '     5 RSD per arrival is not a rate; it is bad data that would migrate'
\echo '     into employee_payroll_value_history and start being paid in phase 3.'
\echo '============================================================'

SELECT e.id AS employee_id,
       e.full_name,
       e.transport_allowance_mode,
       e.transport_allowance_rsd,
       CASE
           WHEN e.transport_allowance_rsd IS NULL          THEN 'no rate — would pay 0'
           WHEN e.transport_allowance_rsd < 50             THEN 'SUSPICIOUS — too small for one arrival'
           WHEN e.transport_allowance_rsd > 2000           THEN 'SUSPICIOUS — looks like a monthly amount, not per arrival'
           ELSE                                                 'plausible per-arrival rate'
       END AS verdict
FROM employees e
WHERE e.archived_at IS NULL
  AND e.is_active
  AND e.transport_allowance_rsd IS NOT NULL
ORDER BY e.transport_allowance_rsd;


\echo ''
\echo '============================================================'
\echo 'Q15. Transport sizing over the month with the MOST recorded work'
\echo '     Q9 reports the period passed on the command line. The 2026-07-31 run'
\echo '     used 2026-07, which held almost no shifts, so it under-reported the'
\echo '     phase 3 exposure. This one picks the busiest month automatically.'
\echo '============================================================'

WITH busiest AS (
    SELECT date_trunc('month', work_date)::date AS month, count(*) AS shifts
    FROM daily_reports
    WHERE archived_at IS NULL AND total_work_minutes > 0
    GROUP BY 1
    ORDER BY shifts DESC
    LIMIT 1
)
SELECT b.month                                                   AS busiest_month,
       count(DISTINCT dr.employee_id)                            AS employees,
       count(*) FILTER (WHERE dr.total_work_minutes > 0)          AS qualifying_shifts,
       sum(coalesce(e.transport_allowance_rsd, 0))
           FILTER (WHERE dr.total_work_minutes > 0)               AS would_pay_total,
       sum(coalesce(i.total_transport_allowance_amount, 0))       AS pays_today_total
FROM busiest b
JOIN daily_reports dr
  ON date_trunc('month', dr.work_date)::date = b.month
 AND dr.archived_at IS NULL
JOIN employees e ON e.id = dr.employee_id
LEFT JOIN payroll_run_items i
       ON i.employee_id = dr.employee_id
      AND i.period = b.month
GROUP BY b.month;


\echo ''
\echo '============================================================'
\echo 'Q16. Forensics on every legacy/adjustment divergence from Q6'
\echo '     The 2026-07-31 run found exactly one (item 1625, 2023-01, meal'
\echo '     700.00 vs 600.00). Phase 4 cannot start until each is explained.'
\echo '============================================================'

SELECT i.id                                AS payroll_run_item_id,
       i.period,
       e.full_name,
       i.meal_allowance_count,
       i.meal_allowance_unit_amount,
       i.meal_allowance_unit_amount_system,
       i.meal_allowance_unit_amount_overridden,
       i.total_meal_allowance_amount        AS item_column,
       a.amount                             AS adjustment_row,
       a.system_amount,
       a.is_overridden,
       a.updated_at                         AS adjustment_updated_at,
       i.updated_at                         AS item_updated_at,
       i.last_calculated_at,
       i.based_on_version,
       mr.version                           AS monthly_report_version
FROM payroll_run_items i
JOIN employees e ON e.id = i.employee_id
LEFT JOIN monthly_reports mr ON mr.id = i.monthly_report_id
LEFT JOIN payroll_adjustments a
       ON a.payroll_run_item_id = i.id
      AND a.payroll_adjustment_category_id =
          (SELECT id FROM payroll_adjustment_categories WHERE code = 'MEAL_ALLOWANCE')
WHERE i.archived_at IS NULL
  AND coalesce(i.total_meal_allowance_amount, 0) <> coalesce(a.amount, 0)
ORDER BY i.period, i.id;


\echo ''
\echo '============================================================'
\echo 'Q17. Historical payroll exposed to a calculation change  (risk R9)'
\echo '     The 2026-07-31 run found 949 items, ALL of them DRAFT and none'
\echo '     locked, back to 2023-01. getForPayrollAccess recalculates any'
\echo '     non-LOCKED item on read, so every one of them silently adopts the'
\echo '     new arithmetic the next time somebody opens it.'
\echo '============================================================'

SELECT date_trunc('year', i.period)::date AS year,
       count(*)                            AS items,
       count(*) FILTER (WHERE i.status = 'LOCKED')    AS locked,
       count(*) FILTER (WHERE i.status <> 'LOCKED')   AS recalculable,
       sum(i.net_payable_amount)                       AS net_payable_total
FROM payroll_run_items i
WHERE i.archived_at IS NULL
GROUP BY 1
ORDER BY 1;


\echo ''
\echo '============================================================'
\echo 'Q18. Which payroll months does PHASE 1 change?  (risk R9)'
\echo '     Phase 1 stops reading the meal/transport rate with now() and reads it'
\echo '     at the payroll period instead. If a rate has only ever had one value,'
\echo '     nothing changes. Every extra period below is a set of payroll months'
\echo '     that will be repriced the next time somebody opens them.'
\echo '============================================================'

SELECT s.setting_key,
       count(*)                                   AS periods,
       min(s.valid_from)                          AS first_valid_from,
       max(s.valid_from)                          AS last_valid_from,
       array_agg(s.setting_value_numeric ORDER BY s.valid_from) AS values_over_time
FROM app_settings s
WHERE s.archived_at IS NULL
  AND s.setting_key IN ('meal_allowance_per_day', 'transport_allowance_per_day')
GROUP BY s.setting_key
ORDER BY s.setting_key;


\echo ''
\echo 'Q18b. Payroll items whose meal amount would be REPRICED by phase 1'
\echo '      Compares the rate stored on the item against the rate that was in'
\echo '      force at the START of that item''s own period. A difference means'
\echo '      the item was priced with a rate from the wrong point in time.'

WITH rate_at_period AS (
    SELECT i.id,
           i.period,
           i.meal_allowance_count,
           i.meal_allowance_unit_amount,
           i.meal_allowance_unit_amount_overridden,
           i.total_meal_allowance_amount,
           (SELECT s.setting_value_numeric
            FROM app_settings s
            WHERE s.setting_key = 'meal_allowance_per_day'
              AND s.archived_at IS NULL
              AND s.is_active
              AND s.valid_from <= i.period::timestamptz
              AND (s.valid_until IS NULL OR s.valid_until >= i.period::timestamptz)
            ORDER BY s.valid_from DESC
            LIMIT 1) AS rate_in_force_then
    FROM payroll_run_items i
    WHERE i.archived_at IS NULL
      AND i.period IS NOT NULL
)
SELECT period,
       count(*)                                                     AS items,
       sum(meal_allowance_count * coalesce(rate_in_force_then, 0)
           - total_meal_allowance_amount)                           AS total_change,
       count(*) FILTER (WHERE meal_allowance_unit_amount_overridden) AS overridden_untouched
FROM rate_at_period
WHERE NOT meal_allowance_unit_amount_overridden
  AND coalesce(rate_in_force_then, 0) <> coalesce(meal_allowance_unit_amount, 0)
GROUP BY period
ORDER BY period;


\echo ''
\echo '============================================================'
\echo 'Q19. app_settings boundary semantics  (deferred alignment migration)'
\echo '     The constraint treats valid_until as EXCLUSIVE (half-open tstzrange);'
\echo '     all four repository queries treat it as INCLUSIVE (valid_until >= :at).'
\echo '     Making the queries exclusive is provably neutral ONLY IF no key has a'
\echo '     CLOSED final period — otherwise the value would stop applying one'
\echo '     microsecond earlier than it does today.'
\echo '============================================================'

SELECT s.setting_key,
       count(*)                                            AS periods,
       count(*) FILTER (WHERE s.valid_until IS NOT NULL)    AS closed_periods,
       bool_or(s.valid_until IS NULL)                       AS has_open_period,
       min(s.valid_from)                                    AS first_valid_from,
       max(coalesce(s.valid_until, 'infinity'::timestamptz)) AS last_valid_until
FROM app_settings s
WHERE s.archived_at IS NULL
GROUP BY s.setting_key
ORDER BY s.setting_key;


\echo ''
\echo 'Q19b. Keys whose LAST period is closed — the only rows the change affects'
\echo '      Expected: 0 rows. Each one needs a decision before the queries flip.'

WITH ranked AS (
    SELECT s.*,
           row_number() OVER (PARTITION BY lower(s.setting_key) ORDER BY s.valid_from DESC) AS rn
    FROM app_settings s
    WHERE s.archived_at IS NULL
)
SELECT setting_key, valid_from, valid_until, setting_value_numeric, is_active
FROM ranked
WHERE rn = 1 AND valid_until IS NOT NULL
ORDER BY setting_key;


\echo ''
\echo 'Q19c. Adjacent periods that TOUCH — where the two conventions disagree'
\echo '      A row means period A ends exactly where B begins. Today both match at'
\echo '      that instant and ORDER BY valid_from DESC picks B, which stays correct'
\echo '      after the change. Listed so the neutrality claim is checkable.'

SELECT a.setting_key,
       a.valid_from  AS a_from, a.valid_until AS a_until, a.setting_value_numeric AS a_value,
       b.valid_from  AS b_from, b.valid_until AS b_until, b.setting_value_numeric AS b_value
FROM app_settings a
JOIN app_settings b
  ON lower(b.setting_key) = lower(a.setting_key)
 AND b.valid_from = a.valid_until
WHERE a.archived_at IS NULL AND b.archived_at IS NULL
ORDER BY a.setting_key, a.valid_from;


\echo ''
\echo '============================================================'
\echo 'Q20. Transport mode vs stored amount  (two-mode rule)'
\echo '     FIXED = paid a fixed MONTHLY amount, whatever they worked.'
\echo '     AUTO  = paid per worked day from app_settings.transport_allowance_per_day.'
\echo '     Only FIXED employees are migrated to TRANSPORT_FIXED_MONTHLY. An AUTO'
\echo '     employee with an amount stored is the row to look at: the amount is'
\echo '     ignored, and if it was meant as a fixed monthly the mode is wrong.'
\echo '============================================================'

SELECT coalesce(e.transport_allowance_mode, '<null>') AS mode,
       count(*)                                        AS employees,
       count(e.transport_allowance_rsd)                AS with_amount,
       count(*) FILTER (WHERE e.transport_allowance_mode <> 'FIXED'
                          AND e.transport_allowance_rsd IS NOT NULL
                          AND e.transport_allowance_rsd > 0) AS amount_that_will_be_ignored
FROM employees e
WHERE e.archived_at IS NULL AND e.is_active
GROUP BY 1
ORDER BY 1;


\echo ''
\echo 'Q20b. AUTO employees carrying an amount — each one needs a decision'
\echo '      Either the mode should be FIXED, or the amount is stale and the'
\echo '      employee is correctly paid per worked day.'

SELECT e.id AS employee_id,
       e.full_name,
       e.transport_allowance_mode,
       e.transport_allowance_rsd,
       (SELECT count(*) FROM daily_reports dr
        WHERE dr.employee_id = e.id AND dr.archived_at IS NULL
          AND dr.total_work_minutes > 0
          AND dr.work_date BETWEEN :'period_start'::date AND :'period_end'::date) AS worked_days_in_period
FROM employees e
WHERE e.archived_at IS NULL
  AND e.is_active
  AND coalesce(e.transport_allowance_mode, 'AUTO') <> 'FIXED'
  AND e.transport_allowance_rsd IS NOT NULL
  AND e.transport_allowance_rsd > 0
ORDER BY e.transport_allowance_rsd DESC;


\echo ''
\echo '============================================================'
\echo 'End of report.'
\echo 'Every query except D0, Q5, Q7, Q9, Q10 and Q11-Q17 should return 0 rows'
\echo 'before the corresponding phase ships. See the plan for which phase.'
\echo '============================================================'
