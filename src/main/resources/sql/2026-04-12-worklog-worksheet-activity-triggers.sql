-- Triggers to track work_logs and work_shifts changes in employee_record_updates
-- This ensures every change (insert/update/delete) on worklogs and workshifts
-- updates the employee_record_updates activity timestamp for the affected employee record.

BEGIN;

-- ============================================================================
-- Function: Track activity for work_shifts (on INSERT, UPDATE, DELETE)
-- ============================================================================
CREATE OR REPLACE FUNCTION trg_work_shifts_track_activity()
RETURNS trigger AS $$
DECLARE
    v_user_id_text text;
    v_user_id bigint;
    v_employee_record_id bigint;
BEGIN
    v_user_id_text := current_setting('app.user_id', true);

    -- If no user context, skip tracking (system/batch operations)
    IF v_user_id_text IS NULL OR v_user_id_text = '' THEN
        RETURN CASE
            WHEN TG_OP = 'DELETE' THEN OLD
            ELSE NEW
        END;
    END IF;

    v_user_id := v_user_id_text::bigint;

    -- Determine the employee_record_id based on operation type
    IF TG_OP = 'DELETE' THEN
        v_employee_record_id := OLD.employee_record_id;
    ELSE
        v_employee_record_id := NEW.employee_record_id;
    END IF;

    -- Insert or update the activity record
    IF v_employee_record_id IS NOT NULL THEN
        INSERT INTO employee_record_updates (employee_record_id, user_id, last_activity_at)
        VALUES (v_employee_record_id, v_user_id, NOW())
        ON CONFLICT (employee_record_id, user_id)
        DO UPDATE SET last_activity_at = EXCLUDED.last_activity_at;
    END IF;

    RETURN CASE
        WHEN TG_OP = 'DELETE' THEN OLD
        ELSE NEW
    END;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS after_insert_work_shifts_track_activity ON work_shifts;
DROP TRIGGER IF EXISTS after_update_work_shifts_track_activity ON work_shifts;
DROP TRIGGER IF EXISTS after_delete_work_shifts_track_activity ON work_shifts;

CREATE TRIGGER after_insert_work_shifts_track_activity
AFTER INSERT ON work_shifts
FOR EACH ROW
EXECUTE FUNCTION trg_work_shifts_track_activity();

CREATE TRIGGER after_update_work_shifts_track_activity
AFTER UPDATE ON work_shifts
FOR EACH ROW
EXECUTE FUNCTION trg_work_shifts_track_activity();

CREATE TRIGGER after_delete_work_shifts_track_activity
AFTER DELETE ON work_shifts
FOR EACH ROW
EXECUTE FUNCTION trg_work_shifts_track_activity();

-- ============================================================================
-- Function: Track activity for work_logs (on INSERT, UPDATE, DELETE)
-- ============================================================================
CREATE OR REPLACE FUNCTION trg_work_logs_track_activity()
RETURNS trigger AS $$
DECLARE
    v_user_id_text text;
    v_user_id bigint;
    v_employee_record_id bigint;
    v_work_shift_id bigint;
BEGIN
    v_user_id_text := current_setting('app.user_id', true);

    -- If no user context, skip tracking (system/batch operations)
    IF v_user_id_text IS NULL OR v_user_id_text = '' THEN
        RETURN CASE
            WHEN TG_OP = 'DELETE' THEN OLD
            ELSE NEW
        END;
    END IF;

    v_user_id := v_user_id_text::bigint;

    -- Determine the work_shift_id based on operation type
    IF TG_OP = 'DELETE' THEN
        v_work_shift_id := OLD.work_shift_id;
    ELSE
        v_work_shift_id := NEW.work_shift_id;
    END IF;

    -- Get the employee_record_id from the work_shift
    SELECT employee_record_id
    INTO v_employee_record_id
    FROM work_shifts
    WHERE id = v_work_shift_id;

    -- Insert or update the activity record
    IF v_employee_record_id IS NOT NULL THEN
        INSERT INTO employee_record_updates (employee_record_id, user_id, last_activity_at)
        VALUES (v_employee_record_id, v_user_id, NOW())
        ON CONFLICT (employee_record_id, user_id)
        DO UPDATE SET last_activity_at = EXCLUDED.last_activity_at;
    END IF;

    RETURN CASE
        WHEN TG_OP = 'DELETE' THEN OLD
        ELSE NEW
    END;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS after_insert_work_logs_track_activity ON work_logs;
DROP TRIGGER IF EXISTS after_update_work_logs_track_activity ON work_logs;
DROP TRIGGER IF EXISTS after_delete_work_logs_track_activity ON work_logs;

CREATE TRIGGER after_insert_work_logs_track_activity
AFTER INSERT ON work_logs
FOR EACH ROW
EXECUTE FUNCTION trg_work_logs_track_activity();

CREATE TRIGGER after_update_work_logs_track_activity
AFTER UPDATE ON work_logs
FOR EACH ROW
EXECUTE FUNCTION trg_work_logs_track_activity();

CREATE TRIGGER after_delete_work_logs_track_activity
AFTER DELETE ON work_logs
FOR EACH ROW
EXECUTE FUNCTION trg_work_logs_track_activity();

COMMIT;

