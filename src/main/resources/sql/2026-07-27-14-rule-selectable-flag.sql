-- =============================================================================
-- "Not offered for selection" is not the same as "not allowed"
-- =============================================================================
-- The common category S must not appear in the work-entry dropdown — it is what
-- work becomes AFTER the mapping, not something anyone performs. But it still
-- has to be fully defined for the calculation, because it is where the money
-- lands and because a log that already carries it must still produce a row.
--
-- I expressed that twice with is_allowed and got it wrong both times:
--   2026-07-27-11 denied it  -> also removed its S -> S definition
--   2026-07-27-13 re-allowed -> put it back in the dropdown
--
-- Those are two different questions and one boolean cannot answer both:
--
--   is_selectable   may a supervisor CHOOSE this when entering work?
--   is_allowed      may the calculation RESOLVE to this at all?
--
-- For every ordinary category both are true. For S, is_selectable is false and
-- is_allowed stays true, so it keeps its coefficient and its self-mapping while
-- disappearing from the picker.
--
-- Default TRUE, so every existing and future rule is selectable unless somebody
-- says otherwise — the same "explicit exclusions only" shape the adjustment
-- rules use.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE work_code_category_scheme_rules
    ADD COLUMN IF NOT EXISTS is_selectable BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN work_code_category_scheme_rules.is_selectable IS
    'Whether a supervisor may choose this category when entering work. FALSE hides it from the allowed-category API and rejects it on submission, while is_allowed = TRUE keeps it fully resolvable as a calculation target.';

DO $$
DECLARE
    v_scheme BIGINT;
    v_all    BIGINT;
BEGIN
    SELECT id INTO v_scheme FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT';
    IF v_scheme IS NULL THEN
        RAISE EXCEPTION 'FOREIGN_FIXED_COEFFICIENT missing; run 2026-07-27-01 first';
    END IF;

    -- By identity, never by code — see 2026-07-27-09.
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
        RAISE NOTICE 'No common effective category found; nothing to do';
        RETURN;
    END IF;

    -- Allowed and self-mapping, but never offered.
    UPDATE work_code_category_scheme_rules
    SET is_selectable = FALSE,
        is_allowed = TRUE,
        effective_category_id = v_all,
        coefficient_override = 1,
        note = 'Calculation target. Work becomes this AFTER the mapping, so it is fully defined here but never offered for selection.'
    WHERE compensation_scheme_id = v_scheme
      AND source_category_id = v_all;

    RAISE NOTICE 'Common effective category (id %) is allowed but not selectable', v_all;
END $$;
