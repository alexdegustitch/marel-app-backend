-- Work calendar: one row per calendar date, classifying it as a workday, weekend/
-- non-working day, official Serbian holiday, or collective leave day. Populated via
-- WorkCalendarService.autoFillYear() (computes official Serbian holidays + weekends)
-- and then editable per-day from the admin UI. Does not gate work_shift creation,
-- but does feed the bonus min-hours/Saturday-eligibility auto-sync (see
-- 2026-07-14-work-calendar-override-and-bonus-active.sql) via working_override.
--
-- One row per date (not a sparse override table) so the calendar UI can render/query
-- a full year directly without computing implicit defaults client-side.

CREATE TABLE IF NOT EXISTS work_calendar_days (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calendar_date   DATE NOT NULL,
    day_type        VARCHAR(30) NOT NULL,
    label           VARCHAR(255),
    working_override BOOLEAN,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT uq_work_calendar_days_calendar_date UNIQUE (calendar_date),
    CONSTRAINT chk_work_calendar_days_day_type
        CHECK (day_type IN ('WORKDAY', 'NON_WORKING', 'HOLIDAY', 'COLLECTIVE_LEAVE'))
);

CREATE INDEX IF NOT EXISTS idx_work_calendar_days_calendar_date ON work_calendar_days (calendar_date);

-- Belt-and-suspenders: if ddl-auto=update created this table before this migration
-- ran (as happened in dev), the CREATE TABLE above is a no-op and the table is left
-- without this default, since Hibernate has no way to express DEFAULT now() from a
-- plain @Column — the JPA entity marks created_at insertable=false and relies on the
-- DB default entirely. Re-assert the default explicitly so it's correct either way.
ALTER TABLE work_calendar_days ALTER COLUMN created_at SET DEFAULT now();

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_work_calendar_days_updated_at'
    ) THEN
        CREATE TRIGGER trg_work_calendar_days_updated_at
            BEFORE UPDATE ON work_calendar_days
            FOR EACH ROW EXECUTE FUNCTION set_updated_at();
    END IF;
END $$;
