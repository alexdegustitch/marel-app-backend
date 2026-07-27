-- =============================================================================
-- work_code_category_scheme_rules — per-scheme category availability,
-- effective category and coefficient override
-- =============================================================================
-- THIS TABLE IS NOT A REPLACEMENT FOR work_code_category_mappings.
-- The two answer different questions and both run, in this order:
--
--   work_code_category_scheme_rules   "for THIS EMPLOYEE's scheme, may this
--                                      source category be used at all, what
--                                      category does the base calculation use,
--                                      and what coefficient applies?"
--
--   work_code_category_mappings       "given the CONTEXT of the work (night
--                                      shift, weekend, parallel machines), what
--                                      additional or derived category does the
--                                      SOURCE category produce?"
--
-- The contextual mappings continue to be keyed on the SOURCE category. A foreign
-- employee working category J on a night shift still resolves the J -> D night
-- mapping; the fixed coefficient changes what the base row is worth, it does not
-- delete the night mapping.
--
-- COLUMN SEMANTICS
--   effective_category_id NULL -> effective category = source category
--   coefficient_override  NULL -> use the existing normal coefficient logic
--                                 (work_code_categories.norm_multiplier)
--   is_allowed = false         -> the category is not selectable under this
--                                 scheme and is rejected on submission, even
--                                 when the scheme allows unmapped categories
--
-- Re-runnable: IF NOT EXISTS on DDL, seeds guarded on (scheme, source, valid_from).
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_code_category_scheme_rules (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    compensation_scheme_id BIGINT        NOT NULL,
    source_category_id     BIGINT        NOT NULL,
    -- NULL = the effective category is the source category itself.
    effective_category_id  BIGINT,
    is_allowed             BOOLEAN       NOT NULL DEFAULT TRUE,
    -- NULL = fall through to the normal coefficient logic. NUMERIC, never a
    -- float: this value multiplies paid minutes.
    --
    -- Scale 2 deliberately matches work_logs.norm_multiplier_snapshot, which is
    -- where a resolved coefficient is recorded. A wider scale here would let an
    -- administrator enter a precision the snapshot column cannot store, and the
    -- rule and the history it produced would silently disagree. Every coefficient
    -- currently in use (work_code_categories.norm_multiplier: 0, 0.6, 1, 1.1,
    -- 1.2, 1.3) has at most one decimal, so this is not a practical limit.
    coefficient_override   NUMERIC(10,2),
    valid_from             DATE          NOT NULL,
    valid_until            DATE,
    note                   TEXT,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    archived_at            TIMESTAMPTZ,

    CONSTRAINT fk_wccsr_scheme
        FOREIGN KEY (compensation_scheme_id) REFERENCES compensation_schemes (id) ON DELETE RESTRICT,
    -- RESTRICT on both category FKs: a rule that has already been snapshotted on
    -- a work log is history. Categories are archived, never deleted.
    CONSTRAINT fk_wccsr_source_category
        FOREIGN KEY (source_category_id) REFERENCES work_code_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wccsr_effective_category
        FOREIGN KEY (effective_category_id) REFERENCES work_code_categories (id) ON DELETE RESTRICT,

    CONSTRAINT chk_wccsr_validity
        CHECK (valid_until IS NULL OR valid_until >= valid_from),
    -- A coefficient of zero would silently zero out pay; a negative one is
    -- meaningless. NULL stays legal and means "use the normal logic".
    CONSTRAINT chk_wccsr_coefficient_positive
        CHECK (coefficient_override IS NULL OR coefficient_override > 0),
    CONSTRAINT chk_wccsr_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_active = TRUE))
);

-- At most one active rule per scheme + source category at any point in time.
-- Same half-open conversion as employee_compensation_scheme_history.
ALTER TABLE work_code_category_scheme_rules
    DROP CONSTRAINT IF EXISTS ex_wccsr_no_overlap;
ALTER TABLE work_code_category_scheme_rules
    ADD CONSTRAINT ex_wccsr_no_overlap
    EXCLUDE USING gist (
        compensation_scheme_id WITH =,
        source_category_id WITH =,
        daterange(valid_from,
                  CASE WHEN valid_until IS NULL THEN NULL ELSE valid_until + 1 END) WITH &&
    ) WHERE (archived_at IS NULL AND is_active = TRUE);

-- The resolver's lookup: scheme + source category + work date.
CREATE INDEX IF NOT EXISTS idx_wccsr_lookup
    ON work_code_category_scheme_rules (compensation_scheme_id, source_category_id, valid_from, valid_until)
    WHERE archived_at IS NULL AND is_active = TRUE;

-- The allowed-category listing: every rule of one scheme on one date.
CREATE INDEX IF NOT EXISTS idx_wccsr_scheme_active
    ON work_code_category_scheme_rules (compensation_scheme_id, valid_from, valid_until)
    WHERE archived_at IS NULL AND is_active = TRUE;

DROP TRIGGER IF EXISTS trg_02_wccsr_archived_at ON work_code_category_scheme_rules;
CREATE TRIGGER trg_02_wccsr_archived_at
    BEFORE UPDATE ON work_code_category_scheme_rules
    FOR EACH ROW EXECUTE FUNCTION set_archived_at_on_deactivate();

DROP TRIGGER IF EXISTS trg_03_wccsr_updated_at ON work_code_category_scheme_rules;
CREATE TRIGGER trg_03_wccsr_updated_at
    BEFORE UPDATE ON work_code_category_scheme_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE work_code_category_scheme_rules IS
    'Per-compensation-scheme rule for one source work-code category: whether it is allowed, which category the base calculation uses, and an optional coefficient override. Distinct from work_code_category_mappings, which handles contextual night/weekend/parallel-machine derivation and is keyed on the source category.';
COMMENT ON COLUMN work_code_category_scheme_rules.effective_category_id IS
    'NULL means the effective category IS the source category. Never erases the source category, which stays on the work log.';
COMMENT ON COLUMN work_code_category_scheme_rules.coefficient_override IS
    'NULL means use work_code_categories.norm_multiplier as before. When set, it wins.';


-- =============================================================================
-- The common effective category for the fixed-coefficient scheme
-- =============================================================================
-- One category, created only if it does not already exist, holding the
-- "all shifts count the same" meaning. norm_multiplier is 1 so that any code
-- path that reads the category's own multiplier agrees with the rule override.
-- type = 'WORK' and the remaining flags copy category J (I, II smena), which is
-- the closest existing analogue, so meal allowance and paid-ness do not change
-- behaviour for these employees.
INSERT INTO work_code_categories (
    category_no, category_name, type, norm_multiplier, is_active, is_paid,
    affects_norm, affects_bonus, fixed_hourly_rate, display_order,
    affects_meal_allowance, base_category, allows_parallel_work, valid_from, note
)
SELECT 'FOREIGN_ALL_SHIFTS',
       'I, II i III smena',
       'WORK',
       1.0,
       TRUE,
       TRUE,
       TRUE,
       TRUE,
       FALSE,
       COALESCE((SELECT max(display_order) FROM work_code_categories), 0) + 1,
       TRUE,
       TRUE,
       FALSE,
       DATE '2026-08-01',
       'Common effective category for the FOREIGN_FIXED_COEFFICIENT scheme. Never selected directly on a work log: it is resolved from a source category (J, D, ...) by work_code_category_scheme_rules. The source category is always preserved.'
-- Either code: the category was later renamed to 'S' through the application,
-- and this guard must not create a second one on a re-run.
WHERE NOT EXISTS (
    SELECT 1 FROM work_code_categories WHERE lower(category_no) IN ('s', 'foreign_all_shifts')
);


-- =============================================================================
-- Rules for FOREIGN_FIXED_COEFFICIENT
-- =============================================================================
-- The scheme has allow_unmapped_categories = false, so ONLY the categories given
-- an explicit rule here are selectable. Two groups:
--
--   1. Shift work categories -> FOREIGN_ALL_SHIFTS with coefficient 1.
--      J  "I, II smena"  (norm_multiplier 1.0)
--      D  "III smena"    (norm_multiplier 1.2)
--      These are the repository's real equivalents of "categories I, II and III".
--      Both remain separately SELECTABLE — the employee still records which shift
--      they actually worked — and both resolve to the same effective category at
--      coefficient 1.
--
--   2. Absence and sick-leave categories -> allowed, NO effective-category
--      remap, NO coefficient override. Blocking these would make it impossible
--      to record leave or sick leave for a foreign employee, which would be a
--      regression, and their pay treatment is a statutory matter that the
--      compensation scheme has no business changing.
--
-- ASSUMPTION, FLAGGED FOR THE BUSINESS: the remaining WORK categories (G, GB, Z,
-- ZB, L, L3, LP, LP3, PL, PLB, JB, DB) are trade- and bonus-specific and are
-- deliberately NOT given rules here, so they are unavailable under this scheme
-- until someone decides what they should resolve to. Adding one is a single
-- INSERT in a follow-up migration — see docs/business-rules/compensation-schemes-and-category-localization.md.
DO $$
DECLARE
    v_from     CONSTANT DATE := DATE '2026-08-01';
    v_scheme   BIGINT;
    v_all_shifts BIGINT;
BEGIN
    SELECT id INTO v_scheme FROM compensation_schemes WHERE code = 'FOREIGN_FIXED_COEFFICIENT';

    -- Resolved by IDENTITY first: the category an existing rule of this scheme
    -- already points at. A category code is administrator-editable — this one
    -- was in fact renamed to 'S' after this script first ran — so keying the
    -- idempotence guard on the code made a re-run raise instead of no-op.
    -- Falls back to either known code, and only then to the insert above.
    SELECT r.effective_category_id INTO v_all_shifts
    FROM work_code_category_scheme_rules r
    WHERE r.compensation_scheme_id = v_scheme
      AND r.effective_category_id IS NOT NULL
    LIMIT 1;

    IF v_all_shifts IS NULL THEN
        SELECT id INTO v_all_shifts FROM work_code_categories
        WHERE lower(category_no) IN ('s', 'foreign_all_shifts')
        ORDER BY CASE lower(category_no) WHEN 's' THEN 0 ELSE 1 END
        LIMIT 1;
    END IF;

    IF v_scheme IS NULL OR v_all_shifts IS NULL THEN
        RAISE EXCEPTION 'FOREIGN_FIXED_COEFFICIENT scheme or its common effective category is missing';
    END IF;

    -- 1. Shift categories -> common effective category at coefficient 1.
    INSERT INTO work_code_category_scheme_rules
        (compensation_scheme_id, source_category_id, effective_category_id,
         is_allowed, coefficient_override, valid_from, note)
    SELECT v_scheme, c.id, v_all_shifts, TRUE, 1, v_from,
           'Fixed coefficient: every shift is worth the same under this scheme. Source category ' || c.category_no || ' is preserved on the work log.'
    FROM work_code_categories c
    WHERE c.category_no IN ('J', 'D')
      AND NOT EXISTS (
          SELECT 1 FROM work_code_category_scheme_rules r
          WHERE r.compensation_scheme_id = v_scheme
            AND r.source_category_id = c.id
            AND r.valid_from = v_from
      );

    -- 2. Absence and sick leave pass through untouched.
    INSERT INTO work_code_category_scheme_rules
        (compensation_scheme_id, source_category_id, effective_category_id,
         is_allowed, coefficient_override, valid_from, note)
    SELECT v_scheme, c.id, NULL, TRUE, NULL, v_from,
           'Passes through unchanged: leave and sick leave are statutory and are not repriced by the compensation scheme.'
    FROM work_code_categories c
    WHERE c.type IN ('ABSENCE', 'SICK_LEAVE')
      AND c.archived_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM work_code_category_scheme_rules r
          WHERE r.compensation_scheme_id = v_scheme
            AND r.source_category_id = c.id
            AND r.valid_from = v_from
      );
END $$;

-- STANDARD intentionally gets NO rules. allow_unmapped_categories = true means
-- every active category resolves to itself with the existing coefficient logic,
-- which is precisely the behaviour that existed before this feature. Seeding
-- identity rules for it would add rows that can only drift out of sync.


-- =============================================================================
-- Audit
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT 'work_code_category_scheme_rules'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = 'work_code_category_scheme_rules');

DROP TRIGGER IF EXISTS trg_audit_logs_work_code_category_scheme_rules ON work_code_category_scheme_rules;
CREATE TRIGGER trg_audit_logs_work_code_category_scheme_rules
    AFTER INSERT OR UPDATE OR DELETE ON work_code_category_scheme_rules
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
