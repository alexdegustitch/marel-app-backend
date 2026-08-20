-- A month's minimum hours for bonus can be set by hand, and the value the work
-- calendar computes is kept beside it rather than replaced.
--
-- WHY. `bonus_min_hours_rules.min_num_hours` was derived from the calendar and
-- rewritten by BonusCalendarSyncService on every calendar edit. A value typed in
-- by a person therefore could not survive: the next edit to any day of that month
-- silently overwrote it. Keeping both numbers is what makes an override possible
-- AND reversible — the calendar keeps maintaining its own answer underneath, so
-- "reset" always has somewhere to return to, and it returns to the CURRENT
-- calendar answer rather than the one that was there when the override was made.
--
-- Additive only: three nullable columns, one generated column, one new table.
-- Nothing existing is rewritten, and `min_num_hours` keeps both its name and its
-- meaning — the calendar's answer.

ALTER TABLE bonus_min_hours_rules
    ADD COLUMN manual_min_num_hours integer,
    ADD COLUMN manual_set_at        timestamptz,
    ADD COLUMN manual_set_by        bigint REFERENCES users (id);

-- The number every reader should use. A generated column rather than a rule
-- everyone has to remember: with COALESCE living in the schema, a query or a
-- calculator cannot accidentally apply the calendar's value to a month somebody
-- has deliberately overridden. Same idiom as employees.full_name and
-- work_logs.duration_min.
ALTER TABLE bonus_min_hours_rules
    ADD COLUMN effective_min_num_hours integer
        GENERATED ALWAYS AS (COALESCE(manual_min_num_hours, min_num_hours)) STORED;

ALTER TABLE bonus_min_hours_rules
    ADD CONSTRAINT chk_bonus_min_hours_rules_manual_positive
        CHECK (manual_min_num_hours IS NULL OR manual_min_num_hours > 0),
    -- Who and when travel together or not at all, so a row can never claim an
    -- override with nobody attached to it.
    ADD CONSTRAINT chk_bonus_min_hours_rules_manual_stamp
        CHECK ((manual_min_num_hours IS NULL) = (manual_set_at IS NULL));


-- The history of that number, as intervals in SYSTEM time.
--
-- The other history tables in this schema (employee_compensation_scheme_history,
-- employee_payroll_value_history) carry valid_from/valid_until as DATES, because
-- what they version is valid over a business period. Here the business period is
-- already a column — `period` names the month the rule is about — so what is
-- versioned is when each value was IN FORCE in the system. That is a timestamp:
-- two corrections on the same afternoon have to be two rows, and a date could not
-- tell them apart.
CREATE TABLE bonus_min_hours_rule_history
(
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period               date        NOT NULL,
    -- Both numbers as they stood, so a row explains itself without a join to a
    -- table that has since moved on.
    system_min_num_hours integer     NOT NULL,
    manual_min_num_hours integer,
    source               varchar(20) NOT NULL,
    valid_from           timestamptz NOT NULL DEFAULT now(),
    valid_until          timestamptz,
    changed_by           bigint REFERENCES users (id),
    note                 text,
    created_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_bmhrh_validity CHECK (valid_until IS NULL OR valid_until >= valid_from),
    CONSTRAINT chk_bmhrh_period_month CHECK (period = date_trunc('month', period)::date),
    CONSTRAINT chk_bmhrh_system_hours CHECK (system_min_num_hours >= 0),
    CONSTRAINT chk_bmhrh_manual_hours CHECK (manual_min_num_hours IS NULL OR manual_min_num_hours > 0),
    CONSTRAINT chk_bmhrh_source CHECK (source IN ('CALENDAR_SYNC', 'MANUAL_SET', 'MANUAL_RESET'))
);

-- At most one open interval per month, enforced by the database rather than hoped
-- for in the service: closing the previous row is a step a future code path could
-- forget, and the failure mode — two rows both claiming to be in force — is the
-- kind that is discovered months later while reading a payslip.
CREATE UNIQUE INDEX uq_bmhrh_one_open_per_period
    ON bonus_min_hours_rule_history (period) WHERE valid_until IS NULL;

CREATE INDEX idx_bmhrh_period_valid_from
    ON bonus_min_hours_rule_history (period, valid_from DESC);


-- Every rule that already exists gets its opening interval, so the history is not
-- blank for everything recorded before today. Sourced as CALENDAR_SYNC because
-- that is what produced these values, and dated from the rule's own creation.
INSERT INTO bonus_min_hours_rule_history (period, system_min_num_hours, manual_min_num_hours,
                                          source, valid_from, changed_by, note)
SELECT period,
       min_num_hours,
       NULL,
       'CALENDAR_SYNC',
       created_at,
       NULL,
       'Početno stanje pri uvođenju istorije.'
FROM bonus_min_hours_rules
WHERE archived_at IS NULL;
