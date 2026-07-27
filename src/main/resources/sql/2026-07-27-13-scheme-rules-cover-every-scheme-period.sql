-- =============================================================================
-- Scheme rules must be in force whenever the scheme itself can be
-- =============================================================================
-- BUG THIS FIXES
-- The rules and the common category were dated from the 2026-08-01 backfill
-- cutover. But a scheme period is assigned by an administrator and can start on
-- ANY date — and one was, from 2026-07-01. For July that left:
--
--     scheme in force  = FOREIGN_FIXED_COEFFICIENT     (allow_unmapped = false)
--     rules in force   = none
--     => every category refused, empty work-entry dropdown
--
-- The mistake was conceptual: I dated the rules as if valid_from were a rollout
-- switch. It is not. A rule says WHAT THE SCHEME MEANS; the scheme period says
-- WHO is under it and WHEN. Only the latter is a rollout date. Rules therefore
-- start early enough that they can never leave a gap under a period somebody
-- assigns later.
--
-- The start date is computed, not hard-coded: the earliest of a 2020-01-01
-- baseline (before the earliest employment start, 2020-08-06, and the earliest
-- work shift, 2023-01-01) and the earliest scheme period that exists. So
-- back-dating a scheme period further cannot reopen the gap.
--
-- This changes no historical calculation. A rule only ever applies to an
-- employee whose SCHEME PERIOD covers the work date, and those periods are
-- unchanged.
--
-- ALSO REVERSES 2026-07-27-11.
-- That script made the common category non-selectable. The business has since
-- confirmed the opposite: the supervisor enters every category except PLO, and
-- the mapping to the common category is the backend's job. So it goes back to
-- being selectable and self-mapping (S -> S at coefficient 1).
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_baseline CONSTANT DATE := DATE '2020-01-01';
    v_scheme   BIGINT;
    v_all      BIGINT;
    v_from     DATE;
    v_rows     INTEGER;
BEGIN
    SELECT id INTO v_scheme FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT';
    IF v_scheme IS NULL THEN
        RAISE EXCEPTION 'FOREIGN_FIXED_COEFFICIENT missing; run 2026-07-27-01 first';
    END IF;

    -- Early enough to cover every scheme period that exists today.
    SELECT LEAST(v_baseline, COALESCE(MIN(valid_from), v_baseline))
    INTO v_from
    FROM employee_compensation_scheme_history
    WHERE archived_at IS NULL;

    -- The common category, by identity: the one the rules point at, else by
    -- either code it has had. See 2026-07-27-09.
    SELECT r.effective_category_id INTO v_all
    FROM work_code_category_scheme_rules r
    WHERE r.compensation_scheme_id = v_scheme
      AND r.effective_category_id IS NOT NULL
    LIMIT 1;

    IF v_all IS NULL THEN
        SELECT id INTO v_all FROM work_code_categories
        WHERE lower(category_no) IN ('s', 'foreign_all_shifts')
        ORDER BY CASE lower(category_no) WHEN 's' THEN 0 ELSE 1 END
        LIMIT 1;
    END IF;

    IF v_all IS NULL THEN
        RAISE EXCEPTION 'The common effective category is missing; run 2026-07-27-09 first';
    END IF;

    -- 1. The category itself has a validity window too, and work_code_categories
    --    .valid_from defaults to CURRENT_DATE — so it was created dated in the
    --    future relative to July work and would have been filtered out even with
    --    a rule in force.
    UPDATE work_code_categories
    SET valid_from = v_from
    WHERE id = v_all AND valid_from > v_from;

    -- 2. Every rule of this scheme starts at the baseline.
    UPDATE work_code_category_scheme_rules
    SET valid_from = v_from
    WHERE compensation_scheme_id = v_scheme
      AND valid_from > v_from;

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    -- 3. Reverse 2026-07-27-11: the common category is selectable again and maps
    --    to itself, so a directly entered S has a defined answer.
    UPDATE work_code_category_scheme_rules
    SET is_allowed = TRUE,
        effective_category_id = v_all,
        coefficient_override = 1,
        note = 'Fixed coefficient. Selectable like any other work category — the supervisor enters what was worked and the backend maps it.'
    WHERE compensation_scheme_id = v_scheme
      AND source_category_id = v_all
      AND is_allowed = FALSE;

    RAISE NOTICE 'Scheme rules start % (% rule(s) moved); common category id % selectable again',
        v_from, v_rows, v_all;
END $$;


-- Adjustment-line rules have the same property: they describe what the scheme
-- means, not when it was rolled out.
UPDATE payroll_adjustment_category_scheme_rules r
SET valid_from = DATE '2020-01-01'
FROM compensation_schemes s
WHERE s.id = r.compensation_scheme_id
  AND s.code = 'FOREIGN_FIXED_COEFFICIENT'
  AND r.valid_from > DATE '2020-01-01';
