-- =============================================================================
-- A basic work operation is WORKED work — and the column now says so
-- =============================================================================
-- WHAT CHANGES
--   · work_code_categories.is_base_operation is RENAMED to
--     is_basic_work_operation.
--   · Its data is corrected: TRUE survives only where it can mean anything —
--     on a category of type 'WORK'. The column decides what the employee
--     form's "Podrazumevana kategorija rada" picker offers, and an absence or
--     a sick leave was only ever TRUE there because the column defaulted to
--     TRUE for every row.
--
-- WHY A RENAME
--   "Base operation" collided with two neighbours that mean other things:
--     · base_category, an unrelated column on this same table;
--     · the resolver's "source category", which some code calls the base.
--   The new name states the whole rule: a category somebody can be ASSIGNED
--   to as their default is a basic WORK operation.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Every version of every non-WORK category (ABSENCE, SICK_LEAVE) is set to
--   FALSE — all versions, not just the open ones, so a re-versioned chain
--   cannot disagree with itself. WORK categories keep whatever an
--   administrator chose. Nothing in the calculation path reads this column
--   (it is a presentation/assignment rule), so no amount moves.
--
--   Guarded; replaying is safe.
--
-- Rollback:
--   ALTER TABLE public.work_code_categories
--       RENAME COLUMN is_basic_work_operation TO is_base_operation;
--   (The data correction is not undone — the old TRUEs on absences were the
--   default talking, not a decision anyone made.)
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'work_code_categories'
          AND column_name = 'is_base_operation'
    ) THEN
        ALTER TABLE public.work_code_categories
            RENAME COLUMN is_base_operation TO is_basic_work_operation;
    END IF;
END $$;

UPDATE public.work_code_categories
SET is_basic_work_operation = false
WHERE is_basic_work_operation
  AND type <> 'WORK';

COMMENT ON COLUMN public.work_code_categories.is_basic_work_operation IS
    'May this category be an employee''s DEFAULT work category. Meaningful only for type = WORK; the šifarnik refuses TRUE on anything else. A presentation/assignment rule: nothing in the calculation reads it.';
