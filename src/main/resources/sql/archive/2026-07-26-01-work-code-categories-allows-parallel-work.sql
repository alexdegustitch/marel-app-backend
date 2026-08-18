-- work_code_categories.allows_parallel_work — the authoritative capability flag
-- marking a category whose operations may run simultaneously with others.
--
-- WHY THIS EXISTS
-- The column was already mapped by the WorkCodeCategory entity and selected by
-- WorkLogRepository, but it had never been written as a migration: it existed in
-- the development database only because spring.jpa.hibernate.ddl-auto=update
-- created it implicitly. Schema validation therefore failed on any database built
-- from the checked-in scripts, and a fresh deployment would have been missing the
-- column entirely.
--
-- The PL/PLB interval classification reads this flag, so it has to be part of the
-- real schema rather than an artefact of dev auto-DDL.
--
-- DEFAULT
-- false — ordinary work. Categories that permit parallel work are marked
-- explicitly through the application, so no data is reclassified by this script.
--
-- Re-runnable: IF NOT EXISTS.

ALTER TABLE public.work_code_categories
    ADD COLUMN IF NOT EXISTS allows_parallel_work boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN public.work_code_categories.allows_parallel_work IS
    'Whether operations in this category may run in parallel with others. '
    'Authoritative source for PL/PLB interval classification; never inferred from category name.';
