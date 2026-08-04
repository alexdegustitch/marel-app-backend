-- =============================================================================
-- BONUS_PERCENTAGE was never a thing this system has
-- =============================================================================
-- 2026-08-01-01 seeded it as "a personal bonus percentage, where the scheme
-- allows one". That was a guess dressed as a catalogue entry. The bonus is not a
-- percentage of anything here: MonthlyBonusCalculator reads a flat amount from
-- the employee's BONUS CATEGORY (bonus_categories.bonus_amount, resolved for the
-- period through employees_bonus_history) and adds a tier from
-- bonus_eligibility_rules. Nothing multiplies by a per-employee percentage, and
-- nothing ever wrote a value for it — the definition has no history rows at all.
--
-- Leaving it would be worse than useless. A catalogue exists so a calculator
-- cannot invent a key; an entry nobody fills is an invitation to write one,
-- against a rule that does not exist.
--
-- Deleted rather than archived: archiving records that something was retired,
-- and this was never in service. fk_epvh_definition_type is ON DELETE RESTRICT,
-- so the guard below is belt and braces — the delete would fail anyway rather
-- than take somebody's values with it.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_id   BIGINT;
    v_rows INTEGER;
BEGIN
    SELECT id INTO v_id
    FROM employee_payroll_value_definitions WHERE code = 'BONUS_PERCENTAGE';

    IF v_id IS NULL THEN
        RAISE NOTICE 'BONUS_PERCENTAGE is not in the catalogue; nothing to remove.';
        RETURN;
    END IF;

    SELECT count(*) INTO v_rows
    FROM employee_payroll_value_history WHERE value_definition_id = v_id;

    IF v_rows > 0 THEN
        RAISE EXCEPTION 'BONUS_PERCENTAGE has % history row(s). Somebody has been using it, '
            'so the premise that it was never in service is wrong — decide what those '
            'values mean before removing the definition.', v_rows;
    END IF;

    DELETE FROM employee_payroll_value_definitions WHERE id = v_id;
    RAISE NOTICE 'BONUS_PERCENTAGE removed from the catalogue. The bonus comes from '
        'bonus_categories through employees_bonus_history, which is where it stays.';
END $$;
