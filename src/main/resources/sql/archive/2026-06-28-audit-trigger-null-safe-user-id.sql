-- Two fixes to audit_trigger_fn:
-- 1) NULL-safe user id: the recalc worker runs without an HTTP user context, so
--    current_setting('app.user_id', true) returns '' (empty string). Casting ''::bigint
--    failed ("invalid input syntax for type bigint"), breaking any recalc-driven write to
--    an audited table. audit_logs.user_id is nullable, so NULLIF(...,'') records NULL user
--    for system writes instead of crashing. User-context writes are unaffected.
-- 2) Exclude effective_work_code_category_id from auditing: it is a derived/system column
--    recomputed on every recalc (the reversible bonus remap). Auditing it would spam the
--    audit log with system changes, so it is skipped in all branches. A recalc that only
--    changes effective_work_code_category_id therefore produces no audit entry at all.

CREATE OR REPLACE FUNCTION public.audit_trigger_fn()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_table_id smallint;
    v_action_id smallint;
    v_changes jsonb := '{}'::jsonb;
    key text;
BEGIN

    -- resolve table id
    SELECT id INTO v_table_id
    FROM audit_tables
    WHERE table_name = TG_TABLE_NAME;

    -- resolve action id
    SELECT id INTO v_action_id
    FROM audit_actions
    WHERE action_name = lower(TG_OP);

    -- INSERT
    IF TG_OP = 'INSERT' THEN

        v_changes := to_jsonb(NEW) - 'updated_at' - 'created_at' - 'effective_work_code_category_id';

        INSERT INTO audit_logs(
            user_id,
            table_id,
            action_id,
            record_id,
            changes
        )
        VALUES (
            NULLIF(current_setting('app.user_id', true), '')::bigint,
            v_table_id,
            v_action_id,
            NEW.id,
            v_changes
        );

        RETURN NEW;

    END IF;


    -- DELETE
    IF TG_OP = 'DELETE' THEN

        v_changes := to_jsonb(OLD) - 'updated_at' - 'created_at' - 'effective_work_code_category_id';

        INSERT INTO audit_logs(
            user_id,
            table_id,
            action_id,
            record_id,
            changes
        )
        VALUES (
            NULLIF(current_setting('app.user_id', true), '')::bigint,
            v_table_id,
            v_action_id,
            OLD.id,
            v_changes
        );

        RETURN OLD;

    END IF;


    -- UPDATE
    IF TG_OP = 'UPDATE' THEN

        FOR key IN
            SELECT jsonb_object_keys(to_jsonb(NEW))
        LOOP

            -- skip audit-irrelevant and derived/system columns
            IF key IN ('created_at','updated_at','effective_work_code_category_id') THEN
                CONTINUE;
            END IF;

            IF (to_jsonb(NEW)->key) IS DISTINCT FROM (to_jsonb(OLD)->key) THEN

                v_changes := v_changes ||
                    jsonb_build_object(
                        key,
                        jsonb_build_object(
                            'old', to_jsonb(OLD)->key,
                            'new', to_jsonb(NEW)->key
                        )
                    );

            END IF;

        END LOOP;

        IF v_changes <> '{}'::jsonb THEN

            INSERT INTO audit_logs(
                user_id,
                table_id,
                action_id,
                record_id,
                changes
            )
            VALUES (
                NULLIF(current_setting('app.user_id', true), '')::bigint,
                v_table_id,
                v_action_id,
                NEW.id,
                v_changes
            );

        END IF;

        RETURN NEW;

    END IF;

    RETURN NULL;

END;
$function$
