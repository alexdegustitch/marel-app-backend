-- =============================================================================
-- A section knows what it does to the number
-- =============================================================================
-- WHAT CHANGES (EXPAND ONLY — nothing is dropped here)
--   · payroll_adjustment_sections — the closed šifarnik of payslip sections.
--     Each row carries the one fact the money formula actually needs, which
--     until now was smeared across two free-text columns per category:
--
--       direction  PLUS  — the line enters the earnings
--                  MINUS — the line enters "previously paid"
--                  NONE  — the line is information and reaches no total
--
--     plus the section's display order (until now duplicated on every
--     category as section_order) and allows_manual_lines, which replaces the
--     hardcoded "hand-made lines go in SETTLEMENTS only" guard with data.
--
--   · payroll_adjustment_categories.section_id — NOT NULL FK, backfilled from
--     section_code. The legacy columns (section_code, impact_code,
--     section_order) STAY, untouched and unread by the code from this version
--     on; a later contract migration retires them once a real payroll month
--     has confirmed the numbers. Until then checkout of the previous code +
--     the rollback below restores yesterday byte for byte.
--
-- WHY THE GUARD BLOCK MAY REFUSE TO RUN
--   The old formula summed the plus side by impact_code (GROSS_PLUS) and the
--   minus side by section_code (SETTLEMENTS). Collapsing both into one
--   direction per section is arithmetically identical ONLY while every
--   section is impact-homogeneous: every category of a PLUS section is
--   GROSS_PLUS and no category elsewhere is. The 2026-10-08 survey of the
--   live catalogue confirmed exactly that; the DO block below re-checks it
--   against WHATEVER database this runs on and aborts the migration — and
--   with it the deploy — rather than let the totals drift silently. An
--   environment it refuses needs its catalogue looked at, not the guard
--   loosened.
--
--   · Rollback: ALTER TABLE payroll_adjustment_categories DROP COLUMN section_id;
--               DELETE FROM audit_tables WHERE table_name = 'payroll_adjustment_sections';
--               DROP TABLE payroll_adjustment_sections;
--               DELETE FROM flyway_schema_history WHERE version = '63';
-- =============================================================================

CREATE TABLE payroll_adjustment_sections (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                varchar(50)  NOT NULL,
    name                varchar(120) NOT NULL,
    direction           varchar(5)   NOT NULL
        CONSTRAINT chk_pas_direction CHECK (direction IN ('PLUS', 'MINUS', 'NONE')),
    sort_order          integer      NOT NULL,
    allows_manual_lines boolean      NOT NULL DEFAULT false,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone DEFAULT now(),
    archived_at         timestamp with time zone,
    CONSTRAINT uq_payroll_adjustment_sections_code UNIQUE (code)
);

-- The closed set. Directions restate what the formula has always done:
-- ADDITIONS and MEAL are the GROSS_PLUS sections, SETTLEMENTS is the one the
-- minus side sums, and PHONE / SETTLEMENTS_SUM / BALANCE reach no total —
-- PHONE settles NEXT month through PHONE_PREVIOUS_MONTH, SETTLEMENTS_SUM
-- mirrors a sum already taken, BALANCE rides in previous_net_payable_amount.
-- Display order comes from the live catalogue where one exists, so no
-- environment sees its payslip sections rearranged by this migration.
INSERT INTO payroll_adjustment_sections (code, name, direction, sort_order, allows_manual_lines)
VALUES
    ('ADDITIONS',       'Dodaci',
     'PLUS',  (SELECT COALESCE(MIN(section_order), 10) FROM payroll_adjustment_categories WHERE section_code = 'ADDITIONS'),       false),
    ('MEAL',            'Topli obrok',
     'PLUS',  (SELECT COALESCE(MIN(section_order), 20) FROM payroll_adjustment_categories WHERE section_code = 'MEAL'),            false),
    ('PHONE',           'Telefon (razdužuje se sledećeg meseca)',
     'NONE',  (SELECT COALESCE(MIN(section_order), 30) FROM payroll_adjustment_categories WHERE section_code = 'PHONE'),           false),
    ('SETTLEMENTS',     'Razduženja',
     'MINUS', (SELECT COALESCE(MIN(section_order), 40) FROM payroll_adjustment_categories WHERE section_code = 'SETTLEMENTS'),     true),
    ('SETTLEMENTS_SUM', 'Ukupno razduženo',
     'NONE',  (SELECT COALESCE(MIN(section_order), 50) FROM payroll_adjustment_categories WHERE section_code = 'SETTLEMENTS_SUM'), false),
    ('BALANCE',         'Saldo iz prethodnog meseca',
     'NONE',  (SELECT COALESCE(MIN(section_order), 60) FROM payroll_adjustment_categories WHERE section_code = 'BALANCE'),         false);

-- ── The equivalence guard ────────────────────────────────────────────────────
DO $$
DECLARE
    bad text;
BEGIN
    -- 1. Every category must belong to a seeded section; an unknown code means
    --    this environment's catalogue drifted and somebody has to look at it.
    SELECT string_agg(DISTINCT c.section_code, ', ')
    INTO bad
    FROM payroll_adjustment_categories c
    WHERE NOT EXISTS (SELECT 1 FROM payroll_adjustment_sections s
                      WHERE s.code = c.section_code);
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION
            'V63 refused: categories reference unknown section(s): %. '
            'Add the section deliberately, with a direction, before migrating.', bad;
    END IF;

    -- 2. Impact-homogeneity: (section is PLUS) must equal (impact is GROSS_PLUS)
    --    for every category, or one direction per section would not reproduce
    --    the old by-impact plus side.
    SELECT string_agg(c.code || ' (' || c.section_code || '/' || c.impact_code || ')', ', ')
    INTO bad
    FROM payroll_adjustment_categories c
    JOIN payroll_adjustment_sections s ON s.code = c.section_code
    WHERE (s.direction = 'PLUS') <> (c.impact_code = 'GROSS_PLUS');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION
            'V63 refused: section direction would change what these categories '
            'do to the totals: %. The catalogue must be impact-homogeneous per '
            'section before the direction can carry the formula.', bad;
    END IF;
END $$;

-- ── The FK, backfilled, then locked ──────────────────────────────────────────
ALTER TABLE payroll_adjustment_categories
    ADD COLUMN section_id bigint
        CONSTRAINT fk_pac_section REFERENCES payroll_adjustment_sections (id);

UPDATE payroll_adjustment_categories c
SET section_id = s.id
FROM payroll_adjustment_sections s
WHERE s.code = c.section_code;

ALTER TABLE payroll_adjustment_categories
    ALTER COLUMN section_id SET NOT NULL;

-- ── House triggers and auditing ──────────────────────────────────────────────
CREATE TRIGGER trg_03_pas_updated_at
    BEFORE UPDATE ON payroll_adjustment_sections
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- A šifarnik that decides what a payslip shows is worth an audit trail.
INSERT INTO public.audit_tables (table_name)
SELECT 'payroll_adjustment_sections'
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables WHERE table_name = 'payroll_adjustment_sections'
);

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_adjustment_sections
    ON public.payroll_adjustment_sections;
CREATE TRIGGER trg_audit_logs_payroll_adjustment_sections
    AFTER INSERT OR DELETE OR UPDATE ON public.payroll_adjustment_sections
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();
