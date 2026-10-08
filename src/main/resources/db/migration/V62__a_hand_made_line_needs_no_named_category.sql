-- =============================================================================
-- A hand-made line needs no named category
-- =============================================================================
-- WHAT CHANGES
--   One column on payroll_adjustment_categories, and one seeded row:
--
--   · seed_on_init — whether payroll initialisation creates this category's
--     row on every item. TRUE — which every existing category keeps — is
--     today's behaviour. The flag exists so a category can be a CARRIER for
--     hand-added lines without the system also printing an empty copy of it
--     on every payslip.
--
--   · the CUSTOM category — the carrier itself. A person adding a line by
--     hand (V59) used to pick one of the named SETTLEMENTS categories and
--     then rename it, which made the choice meaningless and the data
--     misleading: a second "Rata" that is actually an advance still CLAIMS
--     to be an instalment. From now on hand-added lines hang off CUSTOM and
--     the typed display_name is the whole identity. calculation_key is
--     MANUAL, so no calculator will ever address it; seed_on_init is FALSE,
--     so initialisation never creates it; the V59 partial unique index does
--     not cover custom rows, so any number of lines may share it.
--
--   · the scheme matrix rows for CUSTOM — one INHERIT/allowed rule per active
--     compensation scheme (D6: PayrollSchemeScopeService refuses to build a
--     scope while any active category lacks a rule, so a category without its
--     matrix rows does not merely default — it stops every payroll operation
--     for that scheme). Same statement V41 made for a new scheme, transposed.
--
-- WHAT DOES NOT CHANGE
--   Existing hand-added lines keep the category they were created under —
--   they are reached by id and summed by section, so nothing reads that
--   choice. The named SETTLEMENTS categories stay exactly what they are:
--   the system set every payroll is born with.
--
--   · Rollback: DELETE FROM payroll_adjustments
--                   WHERE payroll_adjustment_category_id =
--                       (SELECT id FROM payroll_adjustment_categories WHERE code = 'CUSTOM');
--               DELETE FROM payroll_adjustment_category_scheme_rules
--                   WHERE payroll_adjustment_category_id =
--                       (SELECT id FROM payroll_adjustment_categories WHERE code = 'CUSTOM');
--               DELETE FROM payroll_adjustment_categories WHERE code = 'CUSTOM';
--               ALTER TABLE payroll_adjustment_categories DROP COLUMN seed_on_init;
--               DELETE FROM flyway_schema_history WHERE version = '62';
-- =============================================================================

ALTER TABLE payroll_adjustment_categories
    ADD COLUMN seed_on_init boolean NOT NULL DEFAULT true;

INSERT INTO payroll_adjustment_categories
    (code, name, section_code, section_order, sort_order, impact_code,
     is_manual, calculation_key, editable_input, seed_on_init)
SELECT
    'CUSTOM',
    'Ručna stavka',
    'SETTLEMENTS',
    -- The section's own order and the bottom of its list: a carrier has no
    -- place of its own, and a custom line's position is per-payroll anyway.
    COALESCE((SELECT MIN(section_order) FROM payroll_adjustment_categories
              WHERE section_code = 'SETTLEMENTS'), 0),
    COALESCE((SELECT MAX(sort_order) FROM payroll_adjustment_categories
              WHERE section_code = 'SETTLEMENTS'), 0) + 10,
    'PAYMENT_MINUS',
    true,
    'MANUAL',
    'AMOUNT',
    false
WHERE NOT EXISTS
    (SELECT 1 FROM payroll_adjustment_categories WHERE code = 'CUSTOM');

-- The matrix rows D6 demands: every active scheme states its position on the
-- carrier. INHERIT — the category's own policy (MANUAL, amount typed in) is the
-- whole story, and no scheme calculates a hand-made line differently.
INSERT INTO payroll_adjustment_category_scheme_rules
    (compensation_scheme_id, payroll_adjustment_category_id,
     is_allowed, calculation_mode, valid_from, note)
SELECT s.id, c.id,
       TRUE, 'INHERIT', DATE '2020-01-01',
       'Ručna stavka: nosač ručno dodatih redova razduženja.'
FROM compensation_schemes s
CROSS JOIN payroll_adjustment_categories c
WHERE c.code = 'CUSTOM'
  AND s.is_active
  AND s.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM payroll_adjustment_category_scheme_rules r
      WHERE r.compensation_scheme_id = s.id
        AND r.payroll_adjustment_category_id = c.id
        AND r.archived_at IS NULL);
