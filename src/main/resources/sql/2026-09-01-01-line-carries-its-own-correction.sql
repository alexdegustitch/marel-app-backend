-- =============================================================================
-- Step 1 of moving override state onto the lines: give a line its own
-- system correction, and put what the item columns know onto the rows
-- =============================================================================
-- WHAT IS MISSING TODAY
-- payroll_adjustments records, for every figure a person can change, what the
-- system produced beside it: system_quantity next to quantity, system_unit_amount
-- next to unit_amount, system_amount next to amount. correction_amount is the one
-- exception — it has no system counterpart, so "the additional bonus is 2.000
-- because the rules say so" and "somebody typed 2.000" are the same row.
--
-- That gap is the only thing the line model is short of. Everything else the item
-- columns hold already has somewhere to go:
--
--   meal_allowance_unit_amount (+_overridden)        -> unit_amount + has_manual_input
--   total_transport_allowance_amount (+_overridden)  -> amount + has_manual_input
--   base_bonus_amount (+_overridden)                 -> amount + has_manual_input
--   bonus_correction_amount (+_overridden)           -> correction_amount + has_manual_input
--   total_bonus_amount (+_overridden)                -> is_overridden + override_reason
--
-- THIS MIGRATION CHANGES NO ARITHMETIC. Nothing reads system_correction_amount
-- yet, and nothing sums a line's correction_amount — the recalculation still
-- reads the item columns and still decides everything. This only puts the data
-- where step 2 will read it from, so step 2 runs against complete rows and its
-- results can be compared against the columns before anything is dropped.
--
-- WHY is_overridden IS NOT SET
-- chk_pa_override_reason is VALIDATED since 2026-08-30-01: a row flagged as
-- overridden must say why. These flags predate that rule and carry no reason, and
-- inventing one would put words in somebody's mouth. They get has_manual_input —
-- "a person entered this figure", which is exactly what is known — the same
-- treatment the 24 legacy overrides received.
--
-- WHAT IS DELIBERATELY LEFT ALONE
-- system_amount on the bonus line still holds base + tier, as the calculator
-- writes it. Narrowing it to the base alone changes when a typed total counts as
-- an override, and that is a behaviour change: it belongs to step 2, with the
-- code that reads it. Until then the pair (system_amount, system_correction_amount)
-- is not meant to be read as one figure split in two.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE payroll_adjustments
    ADD COLUMN IF NOT EXISTS system_correction_amount NUMERIC(38, 2) NOT NULL DEFAULT 0;

-- Restated by ALTER as well: ADD COLUMN IF NOT EXISTS skips the whole clause when
-- ddl-auto=update created the column first, NOT NULL and DEFAULT included. See
-- 2026-08-01-01 for what that failure looks like.
UPDATE payroll_adjustments SET system_correction_amount = 0 WHERE system_correction_amount IS NULL;
ALTER TABLE payroll_adjustments
    ALTER COLUMN system_correction_amount SET DEFAULT 0,
    ALTER COLUMN system_correction_amount SET NOT NULL;

COMMENT ON COLUMN payroll_adjustments.system_correction_amount IS
    'What the rules produced for the correction, beside what is effective. For the monthly bonus that is the hours tier from bonus_eligibility_rules — without it, a tier the rules paid cannot be told from one somebody typed.';


DO $$
DECLARE
    v_before  TEXT;
    v_after   TEXT;
    v_meal    INTEGER;
    v_bonus   INTEGER;
    v_manual  INTEGER;
BEGIN
    -- Every amount on every line, before. Compared at the end rather than
    -- asserted in prose: this migration must not move a single figure.
    SELECT md5(string_agg(id::text || ':' || amount::text, ',' ORDER BY id))
      INTO v_before FROM payroll_adjustments;

    -- ── the additional bonus, on the line ────────────────────────────────────
    UPDATE payroll_adjustments a
    SET system_correction_amount = COALESCE(i.bonus_correction_amount_system, 0),
        correction_amount        = COALESCE(i.bonus_correction_amount, 0)
    FROM payroll_run_items i, payroll_adjustment_categories c
    WHERE i.id = a.payroll_run_item_id
      AND c.id = a.payroll_adjustment_category_id
      AND c.code = 'MONTHLY_BONUS'
      AND (a.system_correction_amount IS DISTINCT FROM COALESCE(i.bonus_correction_amount_system, 0)
        OR a.correction_amount        IS DISTINCT FROM COALESCE(i.bonus_correction_amount, 0));
    GET DIAGNOSTICS v_bonus = ROW_COUNT;

    -- ── the meal price a person set ──────────────────────────────────────────
    -- Only where the item says somebody set it. Copying the price onto every meal
    -- line would make the system's own figure look like a human's.
    UPDATE payroll_adjustments a
    SET unit_amount = i.meal_allowance_unit_amount
    FROM payroll_run_items i, payroll_adjustment_categories c
    WHERE i.id = a.payroll_run_item_id
      AND c.id = a.payroll_adjustment_category_id
      AND c.code = 'MEAL_ALLOWANCE'
      AND i.meal_allowance_unit_amount_overridden
      AND a.unit_amount IS DISTINCT FROM i.meal_allowance_unit_amount;
    GET DIAGNOSTICS v_meal = ROW_COUNT;

    -- ── "a person entered this", wherever the item says so ───────────────────
    UPDATE payroll_adjustments a
    SET has_manual_input = TRUE
    FROM payroll_run_items i, payroll_adjustment_categories c
    WHERE i.id = a.payroll_run_item_id
      AND c.id = a.payroll_adjustment_category_id
      AND a.has_manual_input = FALSE
      AND (
            (c.code = 'MEAL_ALLOWANCE'      AND i.meal_allowance_unit_amount_overridden)
         OR (c.code = 'TRANSPORT_ALLOWANCE' AND i.total_transport_allowance_amount_overridden)
         OR (c.code = 'MONTHLY_BONUS'       AND (i.base_bonus_amount_overridden
                                              OR i.bonus_correction_amount_overridden
                                              OR i.total_bonus_amount_overridden))
      );
    GET DIAGNOSTICS v_manual = ROW_COUNT;

    SELECT md5(string_agg(id::text || ':' || amount::text, ',' ORDER BY id))
      INTO v_after FROM payroll_adjustments;

    IF v_before IS DISTINCT FROM v_after THEN
        RAISE EXCEPTION 'An amount changed. This migration writes only the correction, '
            'the meal price and has_manual_input — if a payslip figure moved, something '
            'here is wrong and nothing should be dropped on the strength of it.';
    END IF;

    RAISE NOTICE 'Lines now carry their own correction: % bonus line(s) filled, % meal price(s) '
        'copied, % line(s) marked as entered by a person. No amount changed.',
        v_bonus, v_meal, v_manual;
END $$;
