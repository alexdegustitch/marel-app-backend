-- =============================================================================
-- The list that finds a name by a fragment of it
-- =============================================================================
-- WHAT CHANGES
--   Indexes only. No table, column or row is touched, and nothing here changes
--   what any query returns — only how long it takes to return it.
--
-- WHY
--   The employee directory searches by typing: "mil" should find Milan and
--   Milica as it is typed. The query behind that is
--
--       lower(full_name) LIKE '%mil%'
--
--   and a LIKE with a leading wildcard cannot use a B-tree; with a hundred and
--   forty employees Postgres reads the table and nobody notices, with a million
--   it reads the table on every keystroke. pg_trgm (already installed for the
--   catalogue) indexes the three-letter fragments a value is made of, so the
--   same LIKE becomes an index lookup. The expressions match the SQL the search
--   emits exactly — lower() around the column — because an expression index is
--   only used by a query that spells the expression the same way.
--
--   The remaining indexes serve the directory's other habits over a large table:
--   the default page order (name, then id as a tiebreaker) as an index walk
--   rather than a sort; the probation tile; and the two "open period" joins the
--   row projection makes — the bonus category in force and the scheme in force —
--   each of which is a partial index over exactly the rows the ON clause keeps.
--
-- OPERATIONAL NOTE
--   These are built with plain CREATE INDEX, which Flyway can run inside its
--   transaction. On a table that already holds millions of rows that blocks
--   writes to it for the duration of the build; if that matters for a
--   deployment, build them ahead of time with CREATE INDEX CONCURRENTLY under
--   the same names — IF NOT EXISTS makes this script a no-op for the ones that
--   already exist.
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_employees_full_name_trgm
    ON public.employees USING gin (lower(full_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_employees_employee_no_trgm
    ON public.employees USING gin (lower(employee_no) gin_trgm_ops);

-- Notes are part of the global search too. Free text, so the index is larger
-- than the two above; without it a search would still scan every note.
CREATE INDEX IF NOT EXISTS idx_employees_notes_trgm
    ON public.employees USING gin (lower(notes) gin_trgm_ops)
    WHERE notes IS NOT NULL;

-- The directory only ever lists the unarchived; the default order is by name.
CREATE INDEX IF NOT EXISTS idx_employees_listing_by_name
    ON public.employees (first_name, last_name, id)
    WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_employees_listing_probation
    ON public.employees (probation_end_date)
    WHERE archived_at IS NULL;

-- The bonus category in force: LEFT JOIN ... ON end_date IS NULL.
CREATE INDEX IF NOT EXISTS idx_employees_bonus_history_open
    ON public.employees_bonus_history (employee_id)
    WHERE end_date IS NULL;

-- The scheme in force: LEFT JOIN ... ON valid_until IS NULL AND archived_at IS NULL.
CREATE INDEX IF NOT EXISTS idx_ecsh_open_period
    ON public.employee_compensation_scheme_history (employee_id, compensation_scheme_id)
    WHERE valid_until IS NULL AND archived_at IS NULL;
