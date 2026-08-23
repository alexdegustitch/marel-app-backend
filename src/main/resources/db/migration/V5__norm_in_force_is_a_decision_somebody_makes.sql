-- =============================================================================
-- Which norm is in force is a decision somebody makes, not the newest row
-- =============================================================================
-- WHAT CHANGES
--   1. operation_norm_versions.is_current — the norm the operation works to,
--      stated rather than derived. Until now "current" meant "the newest
--      non-archived version", which made two things impossible: showing the
--      archived ones at all, and putting an EARLIER norm back in force — the
--      ordering would have overruled the decision immediately.
--   2. operation_norm_activations — the chronology of those decisions: which
--      norm was put in force, by whom, when and why. Append-only; an interval
--      "norm X applied from–to" is read from two consecutive entries, or, for
--      the last one, up to the moment its version was archived.
--   3. operation_norm_versions.is_temporary — a norm deliberately entered with
--      NO date, so that "no date" stops being ambiguous between "temporary" and
--      "somebody forgot". The date column shows "Privremena" for these.
--
-- WHAT DOES NOT CHANGE
--   operations.min_norm / max_norm / units_per_product / norm_date keep their
--   meaning exactly: the CURRENT norm, read by payroll and by the manufacturing
--   -time report. Every activation writes them, which is precisely what adding
--   a norm already does today. No existing query has to learn about any of this.
--
-- MIGRATION IMPACT
--   · Additive only. No column is dropped, renamed or retyped.
--   · The backfill marks the version that ALREADY applies under the old rule
--     (newest non-archived, by entry), so nothing changes on any screen the
--     moment this runs. The new succession rule — most recent by norm date,
--     falling back to entry date for temporary norms — governs future archiving
--     only, never a retroactive reshuffle.
--   · Each backfilled row also gets one activation entry, timestamped with the
--     version's own created_at and credited to whoever entered it, so the
--     chronology does not start with a hole.
--   · Every step is guarded (IF NOT EXISTS / NOT EXISTS), so re-running is safe.
--   · Rollback: DROP TABLE operation_norm_activations, then drop the two
--     columns. Nothing else references them.
--
--   A NOTE ON WHAT THIS OPENS. Putting an earlier norm back in force rewrites
--   operations.min_norm, and WorkLogPerformanceCalculator reads that column at
--   calculation time — so it changes future calculations and any RE-calculation
--   of work already entered. That is the same mechanism adding a new norm has
--   always had, not a new one; it is written down here because the decision is
--   now available on an older norm too.
-- =============================================================================

-- === The norm in force is stated ===========================================
ALTER TABLE operation_norm_versions
    ADD COLUMN IF NOT EXISTS is_current boolean NOT NULL DEFAULT false;

-- One norm in force per operation, enforced by the database rather than by
-- whoever remembers to clear the previous flag.
CREATE UNIQUE INDEX IF NOT EXISTS ux_operation_norm_versions_one_current
    ON operation_norm_versions (operation_id)
    WHERE is_current;

-- An archived norm is not in force. Restoring one un-archives it, in the same
-- transaction, so this can never be true of an archived row.
ALTER TABLE operation_norm_versions
    DROP CONSTRAINT IF EXISTS chk_operation_norm_versions_current_not_archived;
ALTER TABLE operation_norm_versions
    ADD CONSTRAINT chk_operation_norm_versions_current_not_archived
        CHECK (NOT is_current OR archived_at IS NULL);

-- === A norm may be deliberately temporary ==================================
ALTER TABLE operation_norm_versions
    ADD COLUMN IF NOT EXISTS is_temporary boolean NOT NULL DEFAULT false;

-- "Temporary" is what a norm without a date IS; a dated temporary norm would be
-- two answers to the same question.
ALTER TABLE operation_norm_versions
    DROP CONSTRAINT IF EXISTS chk_operation_norm_versions_temporary_undated;
ALTER TABLE operation_norm_versions
    ADD CONSTRAINT chk_operation_norm_versions_temporary_undated
        CHECK (NOT is_temporary OR norm_date IS NULL);

-- === The chronology of the decisions =======================================
CREATE TABLE IF NOT EXISTS operation_norm_activations (
    id              bigserial PRIMARY KEY,
    operation_id    bigint NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    norm_version_id bigint NOT NULL REFERENCES operation_norm_versions(id) ON DELETE CASCADE,

    activated_at    timestamptz NOT NULL DEFAULT now(),
    activated_by    bigint REFERENCES users(id) ON DELETE RESTRICT,

    -- Free text, and deliberately not a code list: the reasons are the shop
    -- floor's, and a list invented here would be a business rule nobody agreed.
    reason          text,

    -- Which of the four ways in produced this entry, so the chronology reads as
    -- what happened rather than as a flat list of activations.
    source          text NOT NULL DEFAULT 'ACTIVATED'
        CHECK (source IN ('ADDED', 'EDITED', 'SUCCEEDED', 'ACTIVATED', 'MIGRATED'))
);

CREATE INDEX IF NOT EXISTS idx_operation_norm_activations_operation
    ON operation_norm_activations (operation_id, activated_at DESC, id DESC);

-- === Backfill: state what already applies ==================================
-- The old rule, stated once: newest non-archived version, by entry.
WITH in_force AS (
    SELECT DISTINCT ON (v.operation_id)
           v.id, v.operation_id, v.created_at, v.created_by
    FROM operation_norm_versions v
    WHERE v.archived_at IS NULL
    ORDER BY v.operation_id, v.created_at DESC, v.id DESC
)
UPDATE operation_norm_versions v
   SET is_current = true
  FROM in_force f
 WHERE v.id = f.id
   AND NOT EXISTS (
        SELECT 1 FROM operation_norm_versions c
        WHERE c.operation_id = v.operation_id AND c.is_current
   );

INSERT INTO operation_norm_activations (
    operation_id, norm_version_id, activated_at, activated_by, reason, source
)
SELECT v.operation_id, v.id, v.created_at, v.created_by,
       'Prenos zatečenog stanja pri uvođenju evidencije o primeni normi', 'MIGRATED'
FROM operation_norm_versions v
WHERE v.is_current
  AND NOT EXISTS (
        SELECT 1 FROM operation_norm_activations a WHERE a.operation_id = v.operation_id
  );

-- === Audit coverage, through the mechanism that already exists =============
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['operation_norm_activations']
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
