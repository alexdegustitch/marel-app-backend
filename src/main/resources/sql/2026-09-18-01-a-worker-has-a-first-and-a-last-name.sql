-- =============================================================================
-- A worker has a first name and a last name
-- =============================================================================
-- THE CHANGE
-- employees.full_name was a single writable string, so the two things it
-- actually holds — given name and surname — did not exist as data. You could
-- not sort by surname, filter by surname, or address a worker by their first
-- name, because nothing in the schema knew where one ended and the other began.
--
-- first_name and last_name become the writable source of truth. full_name stays,
-- but as a DERIVED column, exactly as users.full_name has been since
-- 2026-07-15: GENERATED ALWAYS, so it can never disagree with its parts.
--
-- WHY KEEP full_name AT ALL
-- Because "how this worker's name is rendered" is one decision, and roughly
-- forty queries need it — payroll_run_items, employee_records, work_shifts and
-- the analytics facts all select, search and ORDER BY e.full_name. Deriving it
-- in the database means those queries are untouched, every screen renders the
-- name the same way, and no future caller can invent a second spelling. The
-- alternative — concatenating at forty call sites — is the same value computed
-- forty times, with forty chances to drift.
--
-- THE SPLIT
-- Names are stored "Ime Prezime": of the 138 rows, 27 carry a Serbian surname
-- ending on the LAST token and none carry one on the first. So the first token
-- is the given name and everything after it is the surname — which also gets
-- "Nica Broj Jedan" right (Nica / Broj Jedan).
--
-- One row had no surname to split: id 135, "Alda". The owner supplied the full
-- name, Alda Josifović, and it is corrected below by name rather than by id, so
-- this file does not depend on a sequence value. Any OTHER row that cannot be
-- split aborts the migration instead of being guessed at — see the guard.
--
-- SIZING
-- The parts are varchar(120), not varchar(255): full_name must remain
-- varchar(255) so the Hibernate mapping is unchanged, and 120 + 1 + 120 = 241
-- can never overflow it. No human name part comes close to 120 characters.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE employees ADD COLUMN IF NOT EXISTS first_name character varying(120);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS last_name  character varying(120);

-- Backfill. Guarded on first_name IS NULL so a second run is a no-op, and so it
-- can never re-split a name that has already been corrected by hand.
UPDATE employees
SET first_name = split_part(trim(full_name), ' ', 1),
    last_name  = CASE
                     WHEN position(' ' in trim(full_name)) = 0 THEN NULL
                     ELSE trim(substring(trim(full_name) from position(' ' in trim(full_name)) + 1))
                 END
WHERE first_name IS NULL;

-- The one name that carried no surname, supplied by the owner.
UPDATE employees
SET last_name = 'Josifović'
WHERE trim(full_name) = 'Alda' AND last_name IS NULL;

-- Anything still unsplit is a name this migration does not know how to divide.
-- Stop, rather than write a guess into payroll and personnel records.
DO $$
DECLARE
    unsplit INTEGER;
    sample  TEXT;
BEGIN
    SELECT count(*), string_agg(DISTINCT full_name, ', ')
      INTO unsplit, sample
      FROM employees
     WHERE first_name IS NULL OR last_name IS NULL OR trim(last_name) = '';

    IF unsplit > 0 THEN
        RAISE EXCEPTION
            'Cannot split % employee name(s) into first/last: %. Set first_name and last_name by hand for these rows, then re-run.',
            unsplit, sample;
    END IF;
END $$;

ALTER TABLE employees ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE employees ALTER COLUMN last_name  SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_first_name') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_first_name
            CHECK (length(trim(both from first_name)) > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_last_name') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_last_name
            CHECK (length(trim(both from last_name)) > 0);
    END IF;
END $$;

-- full_name stops being independently writable and becomes derived. The
-- is_generated guard makes this a no-op on a second run. Values are unchanged:
-- every row already equals first_name || ' ' || last_name by construction above.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'employees' AND column_name = 'full_name' AND is_generated = 'NEVER'
    ) THEN
        ALTER TABLE employees DROP CONSTRAINT IF EXISTS chk_employees_full_name;
        ALTER TABLE employees DROP COLUMN full_name;
        ALTER TABLE employees ADD COLUMN full_name character varying(255)
            GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED NOT NULL;
    END IF;
END $$;

COMMENT ON COLUMN employees.first_name IS
    'Given name. Written by the application; half of the source of truth for the worker''s name.';

COMMENT ON COLUMN employees.last_name IS
    'Surname. Written by the application; the other half of the source of truth. Sort and filter by surname read this column.';

COMMENT ON COLUMN employees.full_name IS
    'Derived, never written: first_name || '' '' || last_name. Exists so the payroll, employee-record, work-shift and analytics queries have one canonical rendering of a worker''s name that cannot drift from its parts.';
