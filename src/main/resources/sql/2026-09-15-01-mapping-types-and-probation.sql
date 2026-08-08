-- =============================================================================
-- Mapping types become a registry, and one of them stops applying on probation
-- =============================================================================
-- THE RULE: an employee on probation gets no Saturday/Sunday bonus. The night
-- bonus and the multiple-machines (PLB) mapping are unaffected — they still
-- apply from the first day.
--
-- WHY NOT A COMPENSATION SCHEME
-- A scheme is an ASSIGNED dated period, and exactly one must cover every work
-- date or the calculation refuses the day outright. Probation is DERIVED from
-- the employment dates, so making it a scheme means somebody hand-opening a
-- period on hire and closing it 30 days later for every employee — and
-- forgetting the second half breaks work ENTRY, not just the bonus.
--
-- Schemes are also mutually exclusive while probation crosses them: a foreign
-- worker can be on probation. As a scheme it would need STANDARD_PROBATION,
-- FOREIGN_FIXED_COEFFICIENT_PROBATION, COMMERCIAL_PROBATION, and every future
-- scheme would double the set — which is exactly the "add a worker type with
-- data alone" property that makes schemes worth having.
--
-- A scheme answers "what is this work WORTH". A mapping answers "what does this
-- work BECOME, given the context". Probation belongs to the second question, and
-- that question already has one home:
-- DailyRecalcService.resolveApplicableMappingTypes, which already takes the
-- employee and already gates WEEKEND_BONUS on the 180-minute weekly rule.
--
-- WHY A TYPE REGISTRY RATHER THAN A COLUMN ON EACH MAPPING ROW
-- The rule is "no weekend bonus during probation", not "J->JB does not fire".
-- Per-row it would be four rows (J->JB, D->DB, G->GB, Z->ZB) that somebody has
-- to keep in step, and a fifth added later would silently default to the wrong
-- answer. The flag belongs to the TYPE.
--
-- The registry also closes a hole that predates this change: mapping_type is a
-- bare VARCHAR and DailyRecalcService's switch ends in
-- `default -> { /* unknown mapping type: ignore */ }`. A typo therefore creates a
-- row that looks configured and does nothing. The foreign key makes that
-- impossible.
--
-- IN FORCE ALWAYS, not from a date (owner's decision). Contextual mappings are
-- recomputed on every recalculation by design — they are the reversible derived
-- category — so this applies to historical months as they are recalculated.
-- Today that changes nothing: no employee is currently on probation and there is
-- not one weekend shift in the database worked during a probation period.
--
-- Re-runnable.
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_code_category_mapping_types (
    -- Surrogate id because audit_trigger_fn records record_id := NEW.id.
    id                       BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- The stable identifier. Referenced by work_code_category_mappings.mapping_type,
    -- so it is the natural key even though the audit trigger needs an id as well.
    code                     VARCHAR(100) NOT NULL,

    -- Display name for an administration screen. Serbian, like every other
    -- master name in this schema; translations go in a *_translations table if
    -- one is ever needed.
    name                     VARCHAR(150) NOT NULL,

    -- FALSE means this remap does not fire while the employee is on probation.
    -- Defaults TRUE so adding a type later cannot silently withhold a bonus.
    applies_during_probation BOOLEAN      NOT NULL DEFAULT TRUE,

    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    note                     TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ,

    CONSTRAINT uq_wcmt_code UNIQUE (code),
    CONSTRAINT chk_wcmt_code_not_empty CHECK (length(trim(code)) > 0),
    CONSTRAINT chk_wcmt_name_not_empty CHECK (length(trim(name)) > 0)
);

COMMENT ON TABLE work_code_category_mapping_types IS
    'The contextual remap kinds work_code_category_mappings may use. applies_during_probation = FALSE withholds that remap while an employee is within their probation period.';

DROP TRIGGER IF EXISTS trg_03_wcmt_updated_at ON work_code_category_mapping_types;
CREATE TRIGGER trg_03_wcmt_updated_at
    BEFORE UPDATE ON work_code_category_mapping_types
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- =============================================================================
-- The three types the calculation already knows, and the one exclusion
-- =============================================================================
INSERT INTO work_code_category_mapping_types (code, name, applies_during_probation, note)
SELECT v.code, v.name, v.probation, v.note
FROM (VALUES
        ('NIGHT_SHIFT_BONUS',       'Bonus za noćnu smenu',      TRUE,
         'Applies from the first day; probation does not affect it.'),
        ('MULTIPLE_MACHINES_BONUS', 'Bonus za više mašina',      TRUE,
         'Applies from the first day; probation does not affect it.'),
        ('WEEKEND_BONUS',           'Bonus za rad vikendom',     FALSE,
         'Not paid while the employee is on probation — owner rule, in force always.')
     ) AS v(code, name, probation, note)
WHERE NOT EXISTS (SELECT 1 FROM work_code_category_mapping_types t WHERE t.code = v.code);


-- =============================================================================
-- Every existing mapping must name a registered type before the key is added
-- =============================================================================
DO $$
DECLARE
    v_orphans TEXT;
BEGIN
    SELECT string_agg(DISTINCT m.mapping_type, ', ')
      INTO v_orphans
    FROM work_code_category_mappings m
    WHERE NOT EXISTS (SELECT 1 FROM work_code_category_mapping_types t WHERE t.code = m.mapping_type);

    IF v_orphans IS NOT NULL THEN
        RAISE EXCEPTION
            'These mapping_type values are not registered: %. Add them to '
            'work_code_category_mapping_types (deciding applies_during_probation for each) '
            'before this migration can add the foreign key.', v_orphans;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_wccm_mapping_type') THEN
        ALTER TABLE work_code_category_mappings
            ADD CONSTRAINT fk_wccm_mapping_type
            FOREIGN KEY (mapping_type)
            REFERENCES work_code_category_mapping_types (code)
            ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
END $$;


-- =============================================================================
-- Audit — an administrator editing applies_during_probation changes what is paid
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'work_code_category_mapping_types'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'work_code_category_mapping_types');

DROP TRIGGER IF EXISTS trg_audit_logs_work_code_category_mapping_types ON work_code_category_mapping_types;
CREATE TRIGGER trg_audit_logs_work_code_category_mapping_types
    AFTER INSERT OR UPDATE OR DELETE ON work_code_category_mapping_types
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
