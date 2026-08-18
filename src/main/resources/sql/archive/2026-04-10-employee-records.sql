-- Employee record tracking + per-user update activity
BEGIN;

CREATE TABLE IF NOT EXISTS employee_records (
    id bigserial PRIMARY KEY,
    employee_id bigint NOT NULL REFERENCES employees(id),
    start_date date NOT NULL,
    end_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    archived_at timestamptz,
    is_active boolean NOT NULL DEFAULT true
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_employee_records_employee_start_date'
    ) THEN
        ALTER TABLE employee_records
            ADD CONSTRAINT uq_employee_records_employee_start_date
                UNIQUE (employee_id, start_date);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_employee_records_month_window'
    ) THEN
        ALTER TABLE employee_records
            ADD CONSTRAINT chk_employee_records_month_window
                CHECK (
                    start_date = date_trunc('month', start_date)::date
                    AND end_date = (start_date + INTERVAL '1 month - 1 day')::date
                );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_employee_records_employee_active
    ON employee_records (employee_id, is_active, start_date DESC);

CREATE TABLE IF NOT EXISTS employee_record_updates (
    id bigserial PRIMARY KEY,
    employee_record_id bigint NOT NULL REFERENCES employee_records(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id),
    last_activity_at timestamptz NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_employee_record_updates_record_user'
    ) THEN
        ALTER TABLE employee_record_updates
            ADD CONSTRAINT uq_employee_record_updates_record_user
                UNIQUE (employee_record_id, user_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_employee_record_updates_last_activity
    ON employee_record_updates (last_activity_at DESC);

INSERT INTO employee_records (
    employee_id,
    start_date,
    end_date,
    created_at,
    updated_at,
    archived_at,
    is_active
)
SELECT
    ws.employee_id,
    date_trunc('month', ws.work_date)::date,
    (date_trunc('month', ws.work_date)::date + INTERVAL '1 month - 1 day')::date,
    NOW(),
    NOW(),
    NULL,
    true
FROM work_shifts ws
WHERE NOT EXISTS (
    SELECT 1
    FROM employee_records er
    WHERE er.employee_id = ws.employee_id
      AND er.start_date = date_trunc('month', ws.work_date)::date
)
GROUP BY ws.employee_id, date_trunc('month', ws.work_date)::date;

ALTER TABLE work_shifts
    ADD COLUMN IF NOT EXISTS employee_record_id bigint;

UPDATE work_shifts ws
SET employee_record_id = er.id
FROM employee_records er
WHERE ws.employee_record_id IS NULL
  AND er.employee_id = ws.employee_id
  AND er.start_date = date_trunc('month', ws.work_date)::date;

INSERT INTO employee_records (
    employee_id,
    start_date,
    end_date,
    created_at,
    updated_at,
    archived_at,
    is_active
)
SELECT
    ws.employee_id,
    date_trunc('month', ws.work_date)::date,
    (date_trunc('month', ws.work_date)::date + INTERVAL '1 month - 1 day')::date,
    NOW(),
    NOW(),
    NULL,
    true
FROM work_shifts ws
LEFT JOIN employee_records er
    ON er.employee_id = ws.employee_id
   AND er.start_date = date_trunc('month', ws.work_date)::date
WHERE ws.employee_record_id IS NULL
  AND er.id IS NULL
GROUP BY ws.employee_id, date_trunc('month', ws.work_date)::date;

UPDATE work_shifts ws
SET employee_record_id = er.id
FROM employee_records er
WHERE ws.employee_record_id IS NULL
  AND er.employee_id = ws.employee_id
  AND er.start_date = date_trunc('month', ws.work_date)::date;

DO $$
DECLARE
    missing_count bigint;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM work_shifts
    WHERE employee_record_id IS NULL;

    IF missing_count > 0 THEN
        RAISE EXCEPTION 'Cannot set work_shifts.employee_record_id as NOT NULL. Missing rows: %', missing_count;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_work_shifts_employee_record_id'
    ) THEN
        ALTER TABLE work_shifts
            ADD CONSTRAINT fk_work_shifts_employee_record_id
                FOREIGN KEY (employee_record_id)
                REFERENCES employee_records(id);
    END IF;
END $$;

ALTER TABLE work_shifts
    ALTER COLUMN employee_record_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_work_shifts_employee_record_id
    ON work_shifts (employee_record_id);

CREATE OR REPLACE FUNCTION trg_employee_records_touch_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS before_update_employee_records_set_updated_at ON employee_records;
CREATE TRIGGER before_update_employee_records_set_updated_at
BEFORE UPDATE ON employee_records
FOR EACH ROW
EXECUTE FUNCTION trg_employee_records_touch_updated_at();

CREATE OR REPLACE FUNCTION trg_employee_records_track_activity()
RETURNS trigger AS $$
DECLARE
    v_user_id_text text;
    v_user_id bigint;
BEGIN
    v_user_id_text := current_setting('app.user_id', true);

    IF v_user_id_text IS NULL OR v_user_id_text = '' THEN
        RETURN NEW;
    END IF;

    v_user_id := v_user_id_text::bigint;

    INSERT INTO employee_record_updates (employee_record_id, user_id, last_activity_at)
    VALUES (NEW.id, v_user_id, NOW())
    ON CONFLICT (employee_record_id, user_id)
    DO UPDATE SET last_activity_at = EXCLUDED.last_activity_at;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS after_update_employee_records_track_activity ON employee_records;
CREATE TRIGGER after_update_employee_records_track_activity
AFTER UPDATE ON employee_records
FOR EACH ROW
EXECUTE FUNCTION trg_employee_records_track_activity();

CREATE OR REPLACE FUNCTION trg_work_shifts_fill_employee_record_id()
RETURNS trigger AS $$
DECLARE
    v_month_start date;
    v_month_end date;
    v_record_id bigint;
BEGIN
    IF NEW.employee_id IS NULL OR NEW.work_date IS NULL THEN
        RAISE EXCEPTION 'employee_id and work_date are required for work_shifts';
    END IF;

    v_month_start := date_trunc('month', NEW.work_date)::date;
    v_month_end := (v_month_start + INTERVAL '1 month - 1 day')::date;

    IF NEW.employee_record_id IS NOT NULL THEN
        SELECT er.id
        INTO v_record_id
        FROM employee_records er
        WHERE er.id = NEW.employee_record_id
          AND er.employee_id = NEW.employee_id
          AND er.start_date = v_month_start;

        IF v_record_id IS NOT NULL THEN
            RETURN NEW;
        END IF;
    END IF;

    SELECT er.id
    INTO v_record_id
    FROM employee_records er
    WHERE er.employee_id = NEW.employee_id
      AND er.start_date = v_month_start
    LIMIT 1;

    IF v_record_id IS NULL THEN
        INSERT INTO employee_records (
            employee_id,
            start_date,
            end_date,
            is_active
        )
        VALUES (
            NEW.employee_id,
            v_month_start,
            v_month_end,
            true
        )
        ON CONFLICT (employee_id, start_date)
        DO UPDATE SET updated_at = NOW()
        RETURNING id INTO v_record_id;
    END IF;

    NEW.employee_record_id := v_record_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS before_insert_update_work_shifts_fill_employee_record_id ON work_shifts;
CREATE TRIGGER before_insert_update_work_shifts_fill_employee_record_id
BEFORE INSERT OR UPDATE OF employee_id, work_date, employee_record_id ON work_shifts
FOR EACH ROW
EXECUTE FUNCTION trg_work_shifts_fill_employee_record_id();

COMMIT;


