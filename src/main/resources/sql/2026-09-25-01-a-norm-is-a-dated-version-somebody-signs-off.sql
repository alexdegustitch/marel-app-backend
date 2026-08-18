-- =============================================================================
-- A norm is a dated version somebody signs off
-- =============================================================================
-- WHAT CHANGES
--   1. operation_norm_versions — one row per norm an operation has ever had:
--      the values (min/max, quantity in the assembly), the date the norm applies
--      from, who entered it, and — separately — whether and when it was verified
--      ("overena") and by whom.
--   2. operations gains archived_reason / archived_by, so archiving an operation
--      records WHY and BY WHOM, not merely that it happened.
--
-- WHAT DOES NOT CHANGE
--   operations.min_norm / max_norm / units_per_product / norm_date stay exactly
--   where they are and keep their meaning: they are the CURRENT norm, read by
--   payroll, by the manufacturing-time report and by every screen that exists
--   today. The version table is the history behind them, not a replacement — no
--   existing query has to learn about it.
--
-- MIGRATION IMPACT
--   · Additive only. No column is dropped, renamed or retyped.
--   · Every operation that already carries a norm gets ONE seeded version, taken
--     from its current values, timestamped with the operation's own updated_at
--     (falling back to created_at). That row is deliberately NOT marked verified:
--     the database has never recorded a verification, and inventing one would put
--     a signature nobody gave on a historical norm.
--   · Seeding is guarded by NOT EXISTS, so re-running the script is safe.
--   · Rollback is `DROP TABLE operation_norm_versions` plus dropping the two
--     columns; nothing else references them yet.
-- =============================================================================

CREATE TABLE IF NOT EXISTS operation_norm_versions (
    id                bigserial PRIMARY KEY,
    operation_id      bigint NOT NULL REFERENCES operations(id) ON DELETE CASCADE,

    -- The values this version carries. All nullable for the same reason the
    -- columns on operations are: an operation may be normed on some axes only.
    min_norm          integer,
    max_norm          integer,
    units_per_product integer,

    -- The date the norm applies from — the existing "datum norme", unchanged in
    -- meaning. Verification is a separate fact, below.
    norm_date         date,

    note              text,

    created_by        bigint REFERENCES users(id) ON DELETE RESTRICT,
    created_at        timestamptz NOT NULL DEFAULT now(),

    -- "Overa": a person signs off that the norm was measured and holds.
    verified_by       bigint REFERENCES users(id) ON DELETE RESTRICT,
    verified_at       timestamptz,

    archived_at       timestamptz,

    -- A verification is a person AND a moment; half of one is not a fact.
    CONSTRAINT chk_operation_norm_versions_verified_pair
        CHECK ((verified_at IS NULL) = (verified_by IS NULL)),

    -- Same rule the operations table already enforces for a normed row.
    CONSTRAINT chk_operation_norm_versions_norm_range
        CHECK (
            min_norm IS NULL
            OR max_norm IS NULL
            OR (min_norm > 0 AND max_norm > 0 AND min_norm <= max_norm)
        )
);

CREATE INDEX IF NOT EXISTS idx_operation_norm_versions_operation
    ON operation_norm_versions (operation_id, created_at DESC);

-- === Seed: the norm each operation carries today becomes its first version ===
INSERT INTO operation_norm_versions (
    operation_id, min_norm, max_norm, units_per_product, norm_date, created_at, note
)
SELECT
    o.id,
    o.min_norm,
    o.max_norm,
    o.units_per_product,
    o.norm_date,
    COALESCE(o.updated_at, o.created_at, now()),
    'Prenos postojeće norme pri uvođenju istorije normi'
FROM operations o
WHERE (o.min_norm IS NOT NULL OR o.max_norm IS NOT NULL OR o.units_per_product IS NOT NULL OR o.norm_date IS NOT NULL)
  AND NOT EXISTS (
        SELECT 1 FROM operation_norm_versions v WHERE v.operation_id = o.id
  );

-- === Archiving an operation records why, and by whom ========================
ALTER TABLE operations
    ADD COLUMN IF NOT EXISTS archived_reason text,
    ADD COLUMN IF NOT EXISTS archived_by bigint;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_operations_archived_by') THEN
        ALTER TABLE operations
            ADD CONSTRAINT fk_operations_archived_by
            FOREIGN KEY (archived_by) REFERENCES users(id) ON DELETE RESTRICT;
    END IF;
END $$;

-- === Audit coverage, through the mechanism that already exists ==============
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['operation_norm_versions']
    LOOP
        INSERT INTO audit_tables (table_name)
        SELECT t
        WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = t);

        EXECUTE format('DROP TRIGGER IF EXISTS trg_audit_logs_%1$s ON %1$I', t);
        EXECUTE format(
            'CREATE TRIGGER trg_audit_logs_%1$s
                 AFTER INSERT OR UPDATE OR DELETE ON %1$I
                 FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn()', t);
    END LOOP;
END $$;
