-- =============================================================================
-- FOREIGN_FIXED_COEFFICIENT: the authoritative rule set, from the business
-- =============================================================================
-- 2026-07-27-03 seeded a provisional set (only J and D remapped, every absence
-- and sick-leave category passed through) and flagged the rest as an open
-- question. This is the answer, and it changes three things.
--
-- 1. THE COMMON CATEGORY IS CALLED "S", NOT "FOREIGN_ALL_SHIFTS".
--    It was renamed through the application after 03 ran. Every other category
--    code in this system is short (J, D, B, PL, GO...), so the rename is right —
--    but it broke 03's idempotence, because that script resolved the category by
--    its code and would have raised on a re-run. Both scripts now resolve it by
--    IDENTITY (the category an existing rule already points at) and fall back to
--    either code, so a rename can never break them again.
--
-- 2. EVERY WORK CATEGORY RESOLVES TO S AT COEFFICIENT 1.
--    Not just the two shift categories. The full list is below.
--
-- 3. PLO (plaćeno odsustvo) IS NOT AVAILABLE under this scheme.
--    03 let it pass through. It is now an explicit deny row rather than a
--    deleted rule: "we decided this is not allowed" and "nobody has got around
--    to it yet" must not look the same in the data.
--
-- WHY THE FULL WORK LIST, INCLUDING THE BONUS CATEGORIES
-- The calculation order is: contextual mapping FIRST, then the scheme rule on
-- the mapping's result. So the categories a rule has to cover are not only the
-- ones a user can select — they are also the TARGETS the night/weekend/parallel
-- mappings produce (JB, DB, GB, ZB, L3, LP3, PLB). Without rules for those, a
-- foreign employee's night shift would map J -> D -> ... and land on a category
-- the scheme has no answer for.
--
-- Safe to apply in place: no work log references any of these rules
-- (work_code_category_scheme_rule_id IS NULL everywhere) and no shift exists on
-- or after the 2026-08-01 cutover, so no historical calculation can change.
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_from   CONSTANT DATE := DATE '2026-08-01';
    v_scheme BIGINT;
    v_all    BIGINT;
BEGIN
    SELECT id INTO v_scheme FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT';
    IF v_scheme IS NULL THEN
        RAISE EXCEPTION 'FOREIGN_FIXED_COEFFICIENT missing; run 2026-07-27-01 first';
    END IF;

    -- ── Resolve the common category by identity, not by code ────────────────
    -- Priority: whatever the scheme's existing rules already point at (survives
    -- any rename), then either known code, then create it.
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
        INSERT INTO work_code_categories (
            category_no, category_name, type, norm_multiplier, is_active, is_paid,
            affects_norm, affects_bonus, fixed_hourly_rate, display_order,
            affects_meal_allowance, base_category, allows_parallel_work, valid_from, note)
        VALUES ('S', 'I, II i III smena', 'WORK', 1.0, TRUE, TRUE, TRUE, TRUE, FALSE,
                COALESCE((SELECT max(display_order) FROM work_code_categories), 0) + 1,
                TRUE, TRUE, FALSE, v_from,
                'Common effective category for FOREIGN_FIXED_COEFFICIENT. Never selected directly: it is resolved from the worked category. The source category is always preserved.')
        RETURNING id INTO v_all;
    END IF;

    -- Converge the code on databases where 03 created it as FOREIGN_ALL_SHIFTS
    -- and nobody renamed it. A no-op where it is already 'S'.
    UPDATE work_code_categories
    SET category_no = 'S'
    WHERE id = v_all AND lower(category_no) <> 's';

    -- The English name follows the category id, not the code, so a rename never
    -- loses it. Inserted only if absent.
    INSERT INTO work_code_category_translations (work_code_category_id, locale, name)
    SELECT v_all, 'en', '1st, 2nd and 3rd shift'
    WHERE NOT EXISTS (
        SELECT 1 FROM work_code_category_translations
        WHERE work_code_category_id = v_all AND lower(locale) = 'en');

    -- ── Clear the provisional set, then write the authoritative one ─────────
    -- A plain DELETE is correct here precisely because nothing references these
    -- rows yet; once a work log has snapshotted a rule, the rule must be closed
    -- with valid_until instead, never deleted.
    DELETE FROM work_code_category_scheme_rules
    WHERE compensation_scheme_id = v_scheme
      AND NOT EXISTS (SELECT 1 FROM work_logs wl
                      WHERE wl.work_code_category_scheme_rule_id = work_code_category_scheme_rules.id);

    -- 1. Every WORK category -> S at coefficient 1.
    --    Sources the employee selects (J, D, G, Z, L, LP, PL) and the targets the
    --    contextual mappings produce (JB, DB, GB, ZB, L3, LP3, PLB) alike, plus S
    --    itself so the rule set is closed under its own output.
    INSERT INTO work_code_category_scheme_rules
        (compensation_scheme_id, source_category_id, effective_category_id,
         is_allowed, coefficient_override, valid_from, note)
    SELECT v_scheme, c.id, v_all, TRUE, 1, v_from,
           'Fixed coefficient: every shift and every trade is worth the same under this scheme.'
    FROM work_code_categories c
    WHERE upper(c.category_no) IN
          ('PL','PLB','S','L3','D','DB','G','GB','J','JB','L','LP','LP3','Z','ZB')
      AND NOT EXISTS (SELECT 1 FROM work_code_category_scheme_rules r
                      WHERE r.compensation_scheme_id = v_scheme
                        AND r.source_category_id = c.id);

    -- 2. Statutory absence and sick leave pass through untouched: allowed, no
    --    remap, no override, so the category keeps its own coefficient.
    INSERT INTO work_code_category_scheme_rules
        (compensation_scheme_id, source_category_id, effective_category_id,
         is_allowed, coefficient_override, valid_from, note)
    SELECT v_scheme, c.id, NULL, TRUE, NULL, v_from,
           'Passes through unchanged: statutory, and not repriced by the compensation scheme.'
    FROM work_code_categories c
    WHERE upper(c.category_no) IN ('SO','B','B30','BP','ND','GO','NO')
      AND NOT EXISTS (SELECT 1 FROM work_code_category_scheme_rules r
                      WHERE r.compensation_scheme_id = v_scheme
                        AND r.source_category_id = c.id);

    -- 3. PLO explicitly denied. The scheme is closed by default so omitting the
    --    row would have the same effect, but then the data could not tell a
    --    decision apart from an oversight.
    INSERT INTO work_code_category_scheme_rules
        (compensation_scheme_id, source_category_id, effective_category_id,
         is_allowed, coefficient_override, valid_from, note)
    SELECT v_scheme, c.id, NULL, FALSE, NULL, v_from,
           'Not available under this scheme (business decision, 2026-07-27).'
    FROM work_code_categories c
    WHERE upper(c.category_no) = 'PLO'
      AND NOT EXISTS (SELECT 1 FROM work_code_category_scheme_rules r
                      WHERE r.compensation_scheme_id = v_scheme
                        AND r.source_category_id = c.id);

    RAISE NOTICE 'FOREIGN_FIXED_COEFFICIENT rule set: % rows, common category id % (%)',
        (SELECT count(*) FROM work_code_category_scheme_rules WHERE compensation_scheme_id = v_scheme),
        v_all,
        (SELECT category_no FROM work_code_categories WHERE id = v_all);
END $$;
