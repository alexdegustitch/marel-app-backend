-- =============================================================================
-- A neradni dan somebody asked for
-- =============================================================================
-- WHAT CHANGES
--   absence_records.requested_outcome — 'ND' when a person entered the day AS a
--   neradni dan, rather than the allocation deciding it was one.
--
-- WHY A SECOND COLUMN AND NOT A SECOND VALUE IN outcome
--   outcome answers "what did this day turn out to be", and ND there has always
--   meant one thing: the overtime bank covered the whole shift. Storing an
--   unfulfilled request in the same column would make ND mean two things at
--   once, and every reader of it — the payroll, the weekend bonus, the karton —
--   would have to learn which.
--
--   Kept apart, the pair says everything without ambiguity:
--
--     requested = ND, outcome = ND   the request was honoured
--     requested = ND, outcome = NO   asked for, and the bank could not pay
--     requested = NULL               the allocation decided on its own
--
--   The warning the floor asked for — "this employee does not have eight hours"
--   — is exactly the second row, and needs no flag of its own.
--
-- WHY REQUESTS ARE HONOURED FIRST
--   A person marking a specific day as ND is making a choice the chronological
--   rule cannot express: they know which day should be bought back. The
--   allocation covers the requested days first and spends what is left in date
--   order, so the choice survives and the rest of the month keeps its rule.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing. Every existing absence keeps requested_outcome NULL, which means
--   "nobody asked" — and that is exactly true of all of them, since until now
--   ND could not be entered by hand at all.
-- =============================================================================

ALTER TABLE public.absence_records
    ADD COLUMN requested_outcome character varying(20);

-- ND is the only thing anybody can ask for. NO is what an absence already is
-- when nothing is asked, so a request for it would carry no information.
ALTER TABLE public.absence_records
    ADD CONSTRAINT chk_absence_records_requested_outcome
        CHECK ((requested_outcome IS NULL) OR (requested_outcome = 'ND'));

COMMENT ON COLUMN public.absence_records.requested_outcome IS
    'ND when a person entered this day as a neradni dan. Compare with outcome: requested ND and outcome NO means the bank could not pay for it.';

-- The allocation reads one employee-month and has to find the requested days
-- inside it before it spends anything.
CREATE INDEX idx_absence_records_requested_outcome
    ON public.absence_records USING btree (employee_id, requested_outcome)
    WHERE (requested_outcome IS NOT NULL AND is_active = true);
