-- =============================================================================
-- S is a calculation target, not something anybody works
-- =============================================================================
-- 2026-07-27-09 gave S a rule of its own (S -> S at coefficient 1), which had
-- the side effect of listing it in the work-entry dropdown: the allowed-category
-- API offers every category whose rule allows it.
--
-- Nobody works "I, II i III smena". It is where the other categories LAND after
-- the scheme is applied. Denying it removes it from the picker and rejects it if
-- a client posts it directly.
--
-- WHY THIS DOES NOT BREAK THE PAYSLIP
-- Two different questions are being answered by two different code paths:
--
--   "may an employee SELECT this?"     -> the rule's own is_allowed  -> now false
--   "may money LAND on this?"          -> PayrollSchemeScopeService, which marks
--                                         a rule's effective_category_id payable
--                                         whenever that rule is allowed
--
-- The J -> S, D -> S, PL -> S ... rules are all still allowed, so S stays
-- payable and still appears on the payroll sheet. Only its own rule changes.
--
-- The mapping targets never resolve TO S either — no work_code_category_mapping
-- produces S — so the recalc engine never asks whether S itself is allowed.
--
-- effective_category_id is cleared alongside: is_allowed = false short-circuits
-- before the effective category is read, so leaving it set would be a value that
-- can never be used and would only mislead the next reader.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_scheme BIGINT;
    v_all    BIGINT;
    v_rows   INTEGER;
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
        RAISE NOTICE 'No remap rule found for FOREIGN_FIXED_COEFFICIENT; nothing to do';
        RETURN;
    END IF;

    UPDATE work_code_category_scheme_rules
    SET is_allowed = FALSE,
        effective_category_id = NULL,
        note = 'Calculation target only: work lands here after the scheme is applied, so it is never selected directly. Still payable — the rules that remap onto it are what make it appear on the payroll sheet.'
    WHERE compensation_scheme_id = v_scheme
      AND source_category_id = v_all
      AND is_allowed = TRUE;

    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RAISE NOTICE 'Common effective category (id %) is no longer directly selectable (% row(s) updated)',
        v_all, v_rows;
END $$;
