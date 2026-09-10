-- =============================================================================
-- ŠEF SEKTORA — a fourth compensation scheme
-- =============================================================================
-- A sector chief is paid for everything they do at 100 %: the norm does not move
-- their pay, they earn no shift bonus (weekend, night, parallel machines), but
-- they keep the monthly bonus. Like a worker on probation, except permanent and
-- decided by the scheme rather than by the employment dates.
--
-- Two behaviours the existing schemes could not express, so this migration adds
-- two DATA-DRIVEN flags to compensation_schemes rather than naming the scheme in
-- Java (§10 — a scheme added tomorrow must get the right answer with no code
-- change):
--
--   credits_full_performance = TRUE
--       The paid performance coefficient is 100 % regardless of the norm — the
--       same substitution ProbationPolicy already makes, now keyed on the scheme
--       and permanent. The MEASURED efficiency is still computed; it just does
--       not price the month. (Read by WorkLogPerformanceCalculator / DailyRecalc.)
--
--   allows_shift_bonuses = FALSE
--       Withholds the contextual remaps — WEEKEND_BONUS, NIGHT_SHIFT_BONUS,
--       MULTIPLE_MACHINES_BONUS — the same way applies_during_probation withholds
--       WEEKEND_BONUS on probation. (Read by DailyRecalcService.)
--
-- The MONTHLY bonus is untouched: allows_performance_bonus = TRUE, and the
-- adjustment matrix below leaves MONTHLY_BONUS on INHERIT.
--
-- Work categories keep their own weights: allow_unmapped_categories = TRUE, so a
-- category with no scheme rule resolves to itself at its norm_multiplier. No
-- work_code_category_scheme_rules rows are needed.
--
-- ACTIVATION ORDER. Since 2026-09-11 an active scheme must carry a rule for every
-- active payroll_adjustment_category (trg_compensation_scheme_activation_*). So
-- the scheme is inserted INACTIVE, the full adjustment matrix is filled, and only
-- then is it activated — the activation trigger validates against the matrix it
-- now has. Robust to the catalogue being empty at migration time (nothing to
-- require, and runtime treats an absent rule as allowed) and to it being full.
--
-- No employee is moved onto this scheme: who is a sector chief is a decision an
-- administrator makes per person, not something to backfill from a column.
--
-- Re-runnable: guarded throughout.
-- =============================================================================

-- ── The two behaviour flags ─────────────────────────────────────────────────
ALTER TABLE compensation_schemes
    ADD COLUMN IF NOT EXISTS credits_full_performance boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS allows_shift_bonuses     boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN compensation_schemes.credits_full_performance IS
    'When true, worked time is credited at 100 % performance regardless of the norm '
    '(like probation, but permanent and scheme-driven). Read by WorkLogPerformanceCalculator.';
COMMENT ON COLUMN compensation_schemes.allows_shift_bonuses IS
    'When false, the contextual shift remaps (weekend, night, parallel machines) are '
    'withheld for this scheme. Read by DailyRecalcService.resolveApplicableMappingTypes.';

-- ── The scheme, inserted INACTIVE so the activation trigger does not yet fire ──
INSERT INTO compensation_schemes
    (code, name, allow_unmapped_categories, allows_performance_bonus,
     credits_full_performance, allows_shift_bonuses, is_active, note)
SELECT 'SECTOR_CHIEF',
       'Šef sektora',
       TRUE,   -- work categories keep their own weights
       TRUE,   -- keeps the monthly bonus
       TRUE,   -- everything at 100 %, norm ignored for pay
       FALSE,  -- no weekend / night / parallel-machine bonus
       FALSE,  -- activated below, once the adjustment matrix is complete
       'Šef sektora: sve se plaća 100 % bez obzira na normu, bez smenskih bonusa '
       '(vikend/noć/više mašina), ali sa mesečnim bonusom. Kao probni period, samo trajno.'
WHERE NOT EXISTS (SELECT 1 FROM compensation_schemes WHERE code = 'SECTOR_CHIEF');

-- ── The adjustment matrix: every active line behaves normally (INHERIT) ───────
-- Nothing to suppress here — the weekend bonus is a work-category remap, not an
-- adjustment line, and the monthly bonus stays. A rule per active category is
-- what lets the scheme be activated.
INSERT INTO payroll_adjustment_category_scheme_rules
    (compensation_scheme_id, payroll_adjustment_category_id,
     is_allowed, calculation_mode, valid_from, note)
SELECT s.id, c.id,
       TRUE, 'INHERIT', DATE '2020-01-01',
       'Šef sektora: stavka se ponaša standardno.'
FROM compensation_schemes s
CROSS JOIN payroll_adjustment_categories c
WHERE s.code = 'SECTOR_CHIEF'
  AND c.is_active
  AND c.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM payroll_adjustment_category_scheme_rules r
      WHERE r.compensation_scheme_id = s.id
        AND r.payroll_adjustment_category_id = c.id
        AND r.archived_at IS NULL);

-- ── Activate — the trigger validates the matrix we just filled ────────────────
UPDATE compensation_schemes
SET is_active = TRUE
WHERE code = 'SECTOR_CHIEF'
  AND is_active = FALSE;
