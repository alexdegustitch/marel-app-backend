-- =============================================================================
-- A base operation is one somebody can be assigned to
-- =============================================================================
-- THE RULE
-- work_code_categories.is_base_operation says whether a category may be an
-- employee's DEFAULT work category — the one they normally work in. Only these
-- are offered when an employee is created or edited.
--
-- It says nothing about the calculation. A category with is_base_operation =
-- false is still perfectly usable on a work log and still reaches payroll; it
-- just is not something you put on a person as their standing assignment.
--
-- The assignable set the owner defined is G, J, L, LP, PL, S, Z — every one of
-- them type WORK. Everything excluded is either an absence or a sick leave
-- (nobody is "normally assigned" to being ill), or a bonus/variant twin of a
-- category that IS assignable (DB, GB, JB, PLB, ZB, L3, LP3).
--
-- WHY THIS MIGRATION EXISTS AT ALL
-- The column was already present in the owner's database, added by hand: it is
-- in no migration, not in baseline-schema.sql, and was referenced by no Java,
-- no SQL and no screen. So the running database and the schema the tests build
-- had drifted apart — the tests simply never noticed, because nothing read it.
-- This file is what makes the column official, so a fresh environment gets it
-- too.
--
-- IDEMPOTENT IN BOTH DIRECTIONS. On the owner's database the column already
-- exists and its values are left exactly as they are. On a fresh one it is
-- created as TRUE and the known exception is seeded — and that seed runs ONLY
-- on first creation, so a later deliberate change is never flipped back by a
-- re-run.
--
-- Re-runnable.
-- =============================================================================

DO $$
DECLARE
    freshly_added BOOLEAN := FALSE;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'work_code_categories' AND column_name = 'is_base_operation'
    ) THEN
        ALTER TABLE work_code_categories
            ADD COLUMN is_base_operation BOOLEAN NOT NULL DEFAULT TRUE;
        freshly_added := TRUE;
    END IF;

    -- Only on first creation. Re-running must not undo an administrator's later
    -- decision about which categories can be a standing assignment.
    IF freshly_added THEN
        -- Seeded from the set in force on the owner's database, by CODE rather
        -- than by type: "type = WORK" would wrongly include DB, GB, JB, PLB, ZB,
        -- L3 and LP3, which are work categories nobody is assigned to.
        UPDATE work_code_categories
           SET is_base_operation = (category_no IN ('G', 'J', 'L', 'LP', 'PL', 'S', 'Z'));

        RAISE NOTICE 'is_base_operation created; % assignable, % not.',
                     (SELECT count(*) FROM work_code_categories WHERE is_base_operation),
                     (SELECT count(*) FROM work_code_categories WHERE NOT is_base_operation);
    ELSE
        RAISE NOTICE 'is_base_operation already present; % category(ies) currently not assignable — values left untouched.',
                     (SELECT count(*) FROM work_code_categories WHERE NOT is_base_operation);
    END IF;
END $$;

COMMENT ON COLUMN work_code_categories.is_base_operation IS
    'TRUE when this category may be an employee''s default work category — the one they normally work in, offered when creating or editing an employee. FALSE does NOT restrict the calculation: such a category can still be worked and still reaches payroll, it is simply not a standing assignment. Absences, sick leave and the bonus/variant twins are FALSE.';
