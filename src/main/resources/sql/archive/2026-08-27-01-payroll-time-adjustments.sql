-- =============================================================================
-- Time corrections get the same treatment money already has
-- =============================================================================
-- WHAT THIS REPLACES
-- payroll_run_items.manual_adjusted_minutes is one signed integer. It says how
-- many minutes were added and nothing else — not why, not by whom, and it cannot
-- hold two corrections with different causes in the same month. A single number
-- also cannot be un-done selectively: correcting one of two reasons means
-- recomputing the other in your head first.
--
-- These two tables give minutes what payroll_adjustments gives money: a row per
-- correction, a catalogue that says what kinds exist, and an audit trail.
--
-- WHY A SEPARATE TABLE AND NOT A ROW IN payroll_adjustments
-- Every impact_code there moves money into a total — GROSS_PLUS,
-- DEDUCTION_MINUS, PAYMENT_MINUS, BALANCE_PLUS. A minutes row would either be
-- summed into somebody's pay or need a code that means "ignore me", and then
-- every sum-by-impact has to remember the exception. Money and time also round
-- differently, validate differently and are entered differently. They are both
-- manual corrections; they are not the same kind of value.
--
-- WHAT IS DELIBERATELY NOT MIRRORED
--   * No scheme rules. payroll_adjustment_category_scheme_rules exists because
--     D6 requires an explicit per-scheme answer for every money line. Nobody has
--     said time corrections differ by compensation scheme, and inventing that
--     matrix would be inventing a business rule. The catalogue is shaped so the
--     rules table can be added later without touching these rows.
--   * No translations table yet. Same reason — added when a payslip has to show
--     these in the employee's own language.
--
-- SEEDS
-- One category, MANUAL_CORRECTION, which is exactly what
-- manual_adjusted_minutes means today. The list from the design discussion
-- (MISSING_SHIFT, PAID_ABSENCE_CORRECTION, OVERTIME_CORRECTION...) is NOT seeded:
-- those are business rules nobody has stated, and three of them would duplicate
-- records the system already keeps at the source — work_shifts, and
-- monthly_reports.total_absence_paid_minutes together with
-- work_code_categories.type. A correction here must never stand in for fixing
-- the underlying record, or payroll and the norm/efficiency reports end up
-- disagreeing about the same month.
--
-- Re-runnable.
-- =============================================================================

CREATE TABLE IF NOT EXISTS payroll_time_adjustment_categories (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  VARCHAR(100) NOT NULL,
    name                  VARCHAR(150) NOT NULL,
    description           TEXT,

    -- Which total the minutes land in. One value today: the minutes an employee
    -- is paid for. It exists because the first thing another factory is likely
    -- to need is a correction that moves norm minutes without moving payable
    -- ones, and that must not require rewriting the rows already here.
    impact_code           VARCHAR(40)  NOT NULL DEFAULT 'PAYABLE_MINUTES',

    -- MANUAL for a correction a person enters. A key with a calculator behind it
    -- would fill system_minutes instead, exactly as on the money side.
    calculation_key       VARCHAR(100) NOT NULL DEFAULT 'MANUAL',

    -- A correction may only reduce, only increase, or either. Rounding can only
    -- go one way; a missing shift only the other.
    allow_negative        BOOLEAN NOT NULL DEFAULT TRUE,
    allow_positive        BOOLEAN NOT NULL DEFAULT TRUE,

    -- Whether a reason is compulsory. TRUE by default: a change to somebody's
    -- paid time that says nothing about why is the thing this table exists to
    -- stop.
    require_reason        BOOLEAN NOT NULL DEFAULT TRUE,

    visible_in_ui         BOOLEAN NOT NULL DEFAULT TRUE,
    visible_in_pdf        BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order            INTEGER NOT NULL DEFAULT 0,

    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,

    CONSTRAINT chk_ptac_code CHECK (length(trim(code)) > 0),
    CONSTRAINT chk_ptac_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_ptac_impact_code
        CHECK (impact_code IN ('PAYABLE_MINUTES')),
    CONSTRAINT chk_ptac_direction
        CHECK (allow_negative OR allow_positive),
    CONSTRAINT chk_ptac_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_active = TRUE))
);

-- Restated by ALTER so a table ddl-auto created first still converges — see
-- 2026-08-01-01 for how that failure looks.
ALTER TABLE payroll_time_adjustment_categories
    ALTER COLUMN impact_code     SET DEFAULT 'PAYABLE_MINUTES',
    ALTER COLUMN calculation_key SET DEFAULT 'MANUAL',
    ALTER COLUMN allow_negative  SET DEFAULT TRUE,
    ALTER COLUMN allow_positive  SET DEFAULT TRUE,
    ALTER COLUMN require_reason  SET DEFAULT TRUE,
    ALTER COLUMN visible_in_ui   SET DEFAULT TRUE,
    ALTER COLUMN visible_in_pdf  SET DEFAULT TRUE,
    ALTER COLUMN sort_order      SET DEFAULT 0,
    ALTER COLUMN is_active       SET DEFAULT TRUE,
    ALTER COLUMN created_at      SET DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS uq_ptac_code
    ON payroll_time_adjustment_categories (code);

DROP TRIGGER IF EXISTS trg_03_payroll_time_adjustment_categories_updated_at
    ON payroll_time_adjustment_categories;
CREATE TRIGGER trg_03_payroll_time_adjustment_categories_updated_at
    BEFORE UPDATE ON payroll_time_adjustment_categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- =============================================================================
-- The corrections themselves
-- =============================================================================
CREATE TABLE IF NOT EXISTS payroll_time_adjustments (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    payroll_run_item_id   BIGINT NOT NULL,
    payroll_time_adjustment_category_id BIGINT NOT NULL,

    -- What a calculator produced, 0 for a MANUAL category. Kept even though
    -- nothing fills it yet: it is what lets an automatic correction be told
    -- apart from a person's later change to it, which is the distinction the
    -- money side had to be retrofitted with.
    system_minutes        INTEGER NOT NULL DEFAULT 0,

    -- The effective correction. Signed: negative takes time away.
    minutes               INTEGER NOT NULL,

    -- Separates "a person entered this" from "the system computed it", including
    -- the case where they agree.
    has_manual_input      BOOLEAN NOT NULL DEFAULT FALSE,

    reason                TEXT,
    note                  TEXT,

    -- Excluded from the total without being deleted, same as payroll_adjustments.
    is_applied            BOOLEAN NOT NULL DEFAULT TRUE,

    created_by            BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_by             BIGINT,
    edited_at             TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,

    CONSTRAINT fk_pta_item
        FOREIGN KEY (payroll_run_item_id)
        REFERENCES payroll_run_items (id) ON DELETE CASCADE,

    -- RESTRICT, not CASCADE: a category somebody has used is history. Retire it
    -- with is_active = false.
    CONSTRAINT fk_pta_category
        FOREIGN KEY (payroll_time_adjustment_category_id)
        REFERENCES payroll_time_adjustment_categories (id) ON DELETE RESTRICT,

    CONSTRAINT fk_pta_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_pta_edited_by  FOREIGN KEY (edited_by)  REFERENCES users (id) ON DELETE SET NULL,

    -- A correction of zero is not a correction. Absence of a row is how "no
    -- correction" is said here — which is why this table needs no equivalent of
    -- the show_when_zero problem money has.
    CONSTRAINT chk_pta_minutes_nonzero CHECK (minutes <> 0),

    CONSTRAINT chk_pta_no_reactivate
        CHECK (NOT (archived_at IS NOT NULL AND is_applied = TRUE))
);

ALTER TABLE payroll_time_adjustments
    ALTER COLUMN system_minutes   SET DEFAULT 0,
    ALTER COLUMN has_manual_input SET DEFAULT FALSE,
    ALTER COLUMN is_applied       SET DEFAULT TRUE,
    ALTER COLUMN created_at       SET DEFAULT now();

-- The reason is enforced against the CATEGORY's require_reason rather than
-- unconditionally, so a future automatic correction does not have to invent an
-- explanation for arithmetic. Written as a trigger because a CHECK cannot reach
-- another table.
CREATE OR REPLACE FUNCTION payroll_time_adjustment_require_reason()
RETURNS TRIGGER AS $$
DECLARE
    v_require BOOLEAN;
    v_code    TEXT;
BEGIN
    SELECT require_reason, code INTO v_require, v_code
    FROM payroll_time_adjustment_categories
    WHERE id = NEW.payroll_time_adjustment_category_id;

    IF v_require AND (NEW.reason IS NULL OR length(trim(NEW.reason)) = 0) THEN
        RAISE EXCEPTION 'Korekcija vremena "%" mora imati razlog.', v_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_01_pta_require_reason ON payroll_time_adjustments;
CREATE TRIGGER trg_01_pta_require_reason
    BEFORE INSERT OR UPDATE ON payroll_time_adjustments
    FOR EACH ROW EXECUTE FUNCTION payroll_time_adjustment_require_reason();

DROP TRIGGER IF EXISTS trg_03_payroll_time_adjustments_updated_at ON payroll_time_adjustments;
CREATE TRIGGER trg_03_payroll_time_adjustments_updated_at
    BEFORE UPDATE ON payroll_time_adjustments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The read is always "every correction for this item".
CREATE INDEX IF NOT EXISTS idx_pta_item
    ON payroll_time_adjustments (payroll_run_item_id)
    WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pta_category
    ON payroll_time_adjustments (payroll_time_adjustment_category_id);

COMMENT ON TABLE payroll_time_adjustments IS
    'Manual corrections to an employee''s payable minutes, one row per correction. Deliberately NOT payroll_adjustments: every impact_code there moves money into a total, and a minutes row would either be summed into somebody''s pay or need a code every sum has to skip.';
COMMENT ON COLUMN payroll_time_adjustments.minutes IS
    'Signed. The effective correction; negative removes time. Zero is rejected — no row means no correction.';
COMMENT ON COLUMN payroll_time_adjustments.system_minutes IS
    'What a calculator produced. 0 for MANUAL categories. Present so an automatic correction can later be told apart from a person''s change to it.';


-- =============================================================================
-- Audit, same as the money side
-- =============================================================================
INSERT INTO audit_tables (table_name)
SELECT v.table_name
FROM (VALUES ('payroll_time_adjustments'), ('payroll_time_adjustment_categories')) AS v(table_name)
WHERE NOT EXISTS (SELECT 1 FROM audit_tables a WHERE a.table_name = v.table_name);

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_time_adjustments ON payroll_time_adjustments;
CREATE TRIGGER trg_audit_logs_payroll_time_adjustments
    AFTER INSERT OR UPDATE OR DELETE ON payroll_time_adjustments
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

DROP TRIGGER IF EXISTS trg_audit_logs_payroll_time_adjustment_categories
    ON payroll_time_adjustment_categories;
CREATE TRIGGER trg_audit_logs_payroll_time_adjustment_categories
    AFTER INSERT OR UPDATE OR DELETE ON payroll_time_adjustment_categories
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();


-- =============================================================================
-- Seed — only what already exists
-- =============================================================================
INSERT INTO payroll_time_adjustment_categories
    (code, name, description, impact_code, calculation_key, sort_order)
SELECT 'MANUAL_CORRECTION', 'Ručna korekcija vremena',
       'A correction an administrator enters by hand. This is what payroll_run_items.manual_adjusted_minutes has always meant; the row exists so that meaning has a name.',
       'PAYABLE_MINUTES', 'MANUAL', 10
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_time_adjustment_categories WHERE code = 'MANUAL_CORRECTION'
);


-- =============================================================================
-- Backfill — the one existing correction
-- =============================================================================
-- manual_adjusted_minutes is kept and dual-written until it is dropped, exactly
-- as the meal and transport columns are. Existing values become rows so the two
-- agree from the first read.
--
-- No reason can be given for them: nobody recorded one, and writing "migrated"
-- into a field meant for a human explanation would be putting words in somebody's
-- mouth. The category is seeded with require_reason = TRUE, so the trigger would
-- refuse — the backfill therefore relaxes it for the duration and restores it.
DO $$
DECLARE
    v_category_id BIGINT;
    v_rows        INTEGER;
BEGIN
    SELECT id INTO v_category_id
    FROM payroll_time_adjustment_categories WHERE code = 'MANUAL_CORRECTION';

    UPDATE payroll_time_adjustment_categories SET require_reason = FALSE WHERE id = v_category_id;

    INSERT INTO payroll_time_adjustments
        (payroll_run_item_id, payroll_time_adjustment_category_id, minutes,
         has_manual_input, note, created_at)
    SELECT i.id, v_category_id, i.manual_adjusted_minutes, TRUE,
           'Preneto iz payroll_run_items.manual_adjusted_minutes 2026-08-27. Razlog nije zabeležen jer nije ni postojao.',
           COALESCE(i.created_at, now())
    FROM payroll_run_items i
    WHERE i.manual_adjusted_minutes IS NOT NULL
      AND i.manual_adjusted_minutes <> 0
      AND i.archived_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM payroll_time_adjustments t
          WHERE t.payroll_run_item_id = i.id
            AND t.payroll_time_adjustment_category_id = v_category_id
      );
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    UPDATE payroll_time_adjustment_categories SET require_reason = TRUE WHERE id = v_category_id;

    RAISE NOTICE '% existing manual minute correction(s) migrated to payroll_time_adjustments. '
        'payroll_run_items.manual_adjusted_minutes stays as a mirror until it is dropped.', v_rows;
END $$;
