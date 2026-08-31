-- =============================================================================
-- The hours worked over eight can buy a day off
-- =============================================================================
-- WHAT CHANGES
--   overtime_records — new. One row per employee per DAY, holding the minutes
--     worked beyond a regular eight-hour day. Written, updated and removed by
--     the daily recalculation; never by a person.
--   absence_records — four new columns: outcome, compensated_minutes,
--     nd_work_log_id, created_by.
--   absence_compensations — repointed from work_shift_id to overtime_record_id.
--
-- WHY A DAY AND NOT A SHIFT
--   Overtime is a property of the DAY. An employee who works eight hours in the
--   first shift and eight more in the third has worked eight hours of overtime,
--   even though neither shift on its own is longer than a regular one. Keyed by
--   shift, that day is two ordinary shifts and the overtime disappears.
--
--   This is also why absence_compensations had to move. It pointed at a
--   work_shift, so it could say "compensated in some shift" but never "two hours
--   from the 12th and one from the 10th" — which is the whole point of keeping
--   the record. It now points at the overtime day that paid for it.
--
-- WHY overtime_minutes IS STRICTLY POSITIVE
--   A day with no overtime has no row. Zero would be a row asserting a fact
--   nobody needs, and the recalculation would have to decide between writing it
--   and removing it on every pass. The absence of a row IS the zero.
--
-- WHY THESE TABLES ARE NOT REGISTERED FOR AUDIT
--   overtime_records and absence_compensations are DERIVED: recomputed from
--   work_logs and absence_records, both of which are audited already. The
--   allocation rebuilds them from scratch whenever the inputs move, so an audit
--   trail here would record the recomputation rather than a decision, and would
--   grow by the size of the month every time somebody corrects a work log.
--
--   absence_records stays audited, and gains from it: outcome only changes when
--   a day genuinely becomes ND or stops being one, so the audit log answers
--   "when did this become a neradni dan, and when did it stop being one".
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing. absence_records and absence_compensations are both empty (verified
--   before writing this), and no work_log references a category of type ABSENCE
--   or SICK_LEAVE, so no total moves and nothing needs backfilling. The NOT NULL
--   on absence_compensations.overtime_record_id is therefore safe without a
--   default: there is no row for it to reject.
--
-- WHAT IS NOT HERE
--   The ND and NO categories already exist in work_code_categories, both of type
--   ABSENCE with is_paid = false and norm_multiplier = 0, which is exactly what
--   this feature needs. No category is created, altered or seeded by this
--   migration.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The overtime a day carries
-- -----------------------------------------------------------------------------

CREATE TABLE public.overtime_records (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    work_date date NOT NULL,
    overtime_minutes integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT chk_overtime_records_minutes_positive CHECK ((overtime_minutes > 0))
);

ALTER TABLE public.overtime_records ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.overtime_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.overtime_records
    ADD CONSTRAINT overtime_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.overtime_records
    ADD CONSTRAINT fk_overtime_records_employee_id
        FOREIGN KEY (employee_id) REFERENCES public.employees(id) ON DELETE RESTRICT;

-- One row per employee-day, enforced rather than merely intended: the daily
-- recalculation upserts on this key, and two rows for one day would double the
-- bank that day contributes.
ALTER TABLE ONLY public.overtime_records
    ADD CONSTRAINT uq_overtime_records_employee_day UNIQUE (employee_id, work_date);

-- The allocation reads a whole month for one employee at a time.
CREATE INDEX idx_overtime_records_employee_date
    ON public.overtime_records USING btree (employee_id, work_date);

CREATE TRIGGER trg_03_overtime_records_updated_at
    BEFORE UPDATE ON public.overtime_records
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


-- -----------------------------------------------------------------------------
-- 2. What became of an absence
-- -----------------------------------------------------------------------------

ALTER TABLE public.absence_records
    ADD COLUMN outcome character varying(20),
    ADD COLUMN compensated_minutes integer DEFAULT 0 NOT NULL,
    ADD COLUMN nd_work_log_id bigint,
    ADD COLUMN created_by bigint;

-- NO and ND are the only two answers, and NULL is the third: an absence of a
-- category that does not take part in this at all — godišnji odmor, plaćeno
-- odsustvo, službeno odsutan. Those are paid, so there is nothing for the
-- overtime bank to buy back.
--
-- A partly covered NO absence stays NO. compensated_minutes carries how much of
-- it the bank reached; the outcome answers only whether the day became a
-- neradni dan, which needs the WHOLE shift covered.
ALTER TABLE public.absence_records
    ADD CONSTRAINT chk_absence_records_outcome
        CHECK ((outcome IS NULL) OR (outcome IN ('NO', 'ND')));

ALTER TABLE public.absence_records
    ADD CONSTRAINT chk_absence_records_compensated_minutes
        CHECK (((compensated_minutes >= 0) AND (compensated_minutes <= absence_minutes)));

-- ND writes exactly one work_log across the whole shift. Holding its id here is
-- what lets the reverse happen cleanly: when the bank shrinks and the day stops
-- being a neradni dan, there is no guessing which row to remove.
--
-- ON DELETE SET NULL rather than CASCADE: deleting the generated log must not
-- take the absence that explains it.
ALTER TABLE ONLY public.absence_records
    ADD CONSTRAINT fk_absence_records_nd_work_log_id
        FOREIGN KEY (nd_work_log_id) REFERENCES public.work_logs(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.absence_records
    ADD CONSTRAINT fk_absence_records_created_by
        FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_absence_records_nd_work_log
    ON public.absence_records USING btree (nd_work_log_id)
    WHERE (nd_work_log_id IS NOT NULL);

-- The allocation asks for one employee's open absences over a month, which it
-- reaches through the shift. This is the index for the ND side of that scan.
CREATE INDEX idx_absence_records_outcome
    ON public.absence_records USING btree (employee_id, outcome)
    WHERE (is_active = true);


-- -----------------------------------------------------------------------------
-- 3. Which overtime paid for which absence
-- -----------------------------------------------------------------------------

-- The old shape said "compensated in this shift". The new one says "compensated
-- by the overtime of this day", which is the sentence the factory actually needs
-- to read back: three hours on the 21st, two of them from the 12th and one from
-- the 10th.
ALTER TABLE public.absence_compensations
    DROP CONSTRAINT uq_absence_comp_shift;

ALTER TABLE public.absence_compensations
    DROP CONSTRAINT fk_comp_shift;

ALTER TABLE public.absence_compensations
    DROP COLUMN work_shift_id;

ALTER TABLE public.absence_compensations
    ADD COLUMN overtime_record_id bigint NOT NULL;

ALTER TABLE ONLY public.absence_compensations
    ADD CONSTRAINT fk_comp_overtime
        FOREIGN KEY (overtime_record_id) REFERENCES public.overtime_records(id) ON DELETE CASCADE;

-- One row per (absence, overtime day). Two rows would be two answers to "how
-- much of the 21st did the 12th pay for".
ALTER TABLE ONLY public.absence_compensations
    ADD CONSTRAINT uq_absence_comp_overtime UNIQUE (absence_record_id, overtime_record_id);

CREATE INDEX idx_absence_compensations_overtime
    ON public.absence_compensations USING btree (overtime_record_id);

-- archived_at is left on the table and deliberately unused. The allocation
-- rebuilds this table by DELETE and re-INSERT, because a row here is an answer
-- to a calculation rather than an event that happened: soft-deleting would
-- accumulate one dead row per absence per recalculation, and the history it
-- looks like it is keeping is already kept by work_logs and absence_records.
COMMENT ON COLUMN public.absence_compensations.archived_at IS
    'Unused. The allocation rebuilds this table by delete and re-insert; see V25.';
