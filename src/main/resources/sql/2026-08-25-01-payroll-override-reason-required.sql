-- =============================================================================
-- A hard override must say why  (D7)
-- =============================================================================
-- Phase 4 deliberately left this constraint out: the column existed but the patch
-- request had no field to carry a reason, so enforcing it would have rejected
-- ordinary edits that no client could satisfy. Enforcing a rule nobody can comply
-- with does not make the data honest, it makes the feature unusable.
--
-- The field ships with this migration (AdjustmentPatchDto.overrideReason), the
-- service refuses a total override without one, and the constraint is what stops
-- a direct write going around both.
--
-- NOT VALID, and that is the whole point of using it here. Overrides recorded
-- before today have no reason, and inventing one would be putting words in
-- somebody's mouth about a decision they made months ago. The rule binds every new
-- and updated row from now, which is what it is for. Existing rows keep their flag
-- and their silence, and the audit trail still says who and when.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE payroll_adjustments
    DROP CONSTRAINT IF EXISTS chk_pa_override_reason;
ALTER TABLE payroll_adjustments
    ADD CONSTRAINT chk_pa_override_reason
    CHECK (is_overridden = FALSE
           OR (override_reason IS NOT NULL AND length(trim(override_reason)) > 0))
    NOT VALID;

COMMENT ON CONSTRAINT chk_pa_override_reason ON payroll_adjustments IS
    'A typed-in total must carry a reason. NOT VALID on purpose: rows overridden before 2026-08-25 have none, and back-filling one would invent an explanation. Run VALIDATE CONSTRAINT only after those rows have been given a real reason or archived.';

DO $$
DECLARE
    v_legacy INTEGER;
BEGIN
    SELECT count(*) INTO v_legacy
    FROM payroll_adjustments
    WHERE is_overridden = TRUE
      AND (override_reason IS NULL OR length(trim(override_reason)) = 0);

    RAISE NOTICE '% existing override(s) carry no reason. They are left as they are; '
        'the constraint applies from now on. VALIDATE CONSTRAINT once they are dealt with.',
        v_legacy;
END $$;
