-- =============================================================================
-- The old vocabulary retires            *** PENDING — NOT A LIVE MIGRATION ***
-- =============================================================================
-- This file DELIBERATELY lives in docs/pending-migrations/, outside
-- src/main/resources/db/migration, so Flyway cannot pick it up. It is the
-- CONTRACT half of V63's expand/contract pair and is applied only after a
-- real payroll month has run on V63 and the owner has explicitly approved it.
--
-- TO APPLY (one PR, all together):
--   1. Move this file to src/main/resources/db/migration/V64__the_old_vocabulary_retires.sql
--      (renumber if other migrations landed in between).
--   2. Remove from PayrollAdjustmentCategory: sectionCode, sectionOrder,
--      impactCode, inputType, overrideTarget, isManual, allowOverride —
--      and every remaining setter call / DTO passthrough:
--        · PayrollAdjustmentCategoryService: stop writing the legacy columns;
--          derive nothing — section is already the source.
--        · PayrollAdjustmentCategoryResponse / PayrollAdjustmentDetailDto /
--          PayrollAdjustmentResponse: impactCode becomes a DERIVED legacy
--          token for wire compatibility (PLUS→GROSS_PLUS, MINUS→PAYMENT_MINUS,
--          NONE→INFO_ONLY) or is dropped outright together with the frontend
--          fields (lookups registry shows "Uticaj"; frozen documents only
--          need sectionCode, which stays).
--        · PayrollScenarioFixture CATALOGUE: drop the legacy columns.
--      adjustmentPolicy.ts (frontend): remove the isManual/allowOverride
--      legacy fallbacks (editableInput/allowTotalOverride are the source).
--   3. Hibernate ddl-auto=validate will fail the build if any entity still
--      maps a dropped column — that is the safety net, not an obstacle.
--
-- WHAT IT DROPS AND WHY IT IS SAFE
--   · section_code, section_order, impact_code — superseded by section_id
--     (V63); unread by any calculation since V63, and V63's guard proved the
--     direction model reproduces the old arithmetic on this database.
--   · input_type, override_target — dead: written by CRUD, read by nothing,
--     superseded by editable_input/allow_total_override (phase 3).
--   · is_manual, allow_override — legacy half of the same phase-3 story,
--     kept only as frontend fallbacks until step 2 above removes those.
--   Locked payrolls are untouched: the frozen jsonb document and the
--   *_snapshot columns on payroll_adjustments keep the old vocabulary
--   forever, and PayrollTotals.legacyDirection keeps translating it.
--
--   · Rollback (restores columns, NOT their values — take a pg_dump first;
--     values are recoverable from the sections seed and the audit trail):
--       ALTER TABLE payroll_adjustment_categories
--           ADD COLUMN section_code varchar(255),
--           ADD COLUMN section_order integer,
--           ADD COLUMN impact_code varchar(255),
--           ADD COLUMN input_type varchar(255),
--           ADD COLUMN override_target varchar(255),
--           ADD COLUMN is_manual boolean NOT NULL DEFAULT true,
--           ADD COLUMN allow_override boolean NOT NULL DEFAULT false;
--       UPDATE payroll_adjustment_categories c
--       SET section_code = s.code, section_order = s.sort_order,
--           impact_code = CASE s.direction WHEN 'PLUS' THEN 'GROSS_PLUS'
--                                          WHEN 'MINUS' THEN 'PAYMENT_MINUS'
--                                          ELSE 'INFO_ONLY' END
--       FROM payroll_adjustment_sections s WHERE s.id = c.section_id;
--       DELETE FROM flyway_schema_history WHERE version = '64';
-- =============================================================================

ALTER TABLE payroll_adjustment_categories
    DROP COLUMN section_code,
    DROP COLUMN section_order,
    DROP COLUMN impact_code,
    DROP COLUMN input_type,
    DROP COLUMN override_target,
    DROP COLUMN is_manual,
    DROP COLUMN allow_override;
