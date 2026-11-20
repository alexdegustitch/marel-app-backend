-- =============================================================================
-- A session can say it is not being audited
-- =============================================================================
-- WHAT CHANGES
--   audit_trigger_fn() — the one function all 68 audit triggers call — gains a
--   guard as its first statement:
--
--       IF current_setting('app.audit_off', true) = 'on' THEN RETURN ...
--
--   Nothing else in the function moves: the body below is V10's verbatim,
--   password_hash exclusion and all, with the guard inserted after BEGIN. No
--   trigger, table or column is touched, so this migration takes no table
--   locks and the running application does not notice it.
--
-- HOW TO USE IT
--   In the session that is about to do unaudited work:
--
--       BEGIN;
--       SET LOCAL app.audit_off = 'on';   -- or SET, for the whole session
--       ...                               -- bulk import, data migration
--       COMMIT;
--
--   SET LOCAL ends with the transaction; a plain SET ends with the connection.
--   Either way nothing survives it: no DDL, nothing to remember to undo, and
--   no other session is affected — the application keeps writing its trail
--   throughout. To check where you stand:
--
--       SELECT coalesce(current_setting('app.audit_off', true), '(nije set)');
--
--   The switch is deliberately fail-safe: only the exact string 'on' silences
--   the trail. 'ON', 'true', '1', a typo or an unset value all keep auditing.
--
-- WHAT IT IS NOT
--   · The application never sets it. No Java code reads or writes this GUC;
--     a payroll or karton change made through the app is audited as before.
--   · It is not the same tool as scripts/audit-triggers-off.sql. That script
--     disables the triggers for the WHOLE database, every session included,
--     and survives a restart — the right thing when the application itself
--     must run unaudited, and the thing that can be forgotten. This switch is
--     per-session and cannot be forgotten, and therefore cannot silence the
--     application either. Keep both; they answer different questions.
--   · It is not a privilege boundary. Any session that can connect can set a
--     custom GUC, so this does not stop anybody who could already write to
--     these tables from writing without a trail. In this deployment the
--     application and the operator are the same database role (the owner),
--     which could already run ALTER TABLE ... DISABLE TRIGGER, so the switch
--     adds convenience, not a new hole. If the roles are ever split, the
--     application's role should not be the one that can set it — and that is
--     a decision for whoever splits them, not a guess made here.
--   · scripts/audit-triggers-status.sql cannot see it. The switch lives in a
--     session, not in the catalogue; the status script reports the triggers,
--     which stay enabled the whole time.
--
-- WHAT THE TRAIL LOOKS LIKE AFTERWARDS
--   Exactly as if the rows had never changed: no row in audit_logs, and
--   nothing marking the gap. That is the point of the tool and the reason to
--   reach for it only for a known, deliberate, unaudited job.
--
--   Replaying is safe — CREATE OR REPLACE is idempotent.
--
-- ROLLBACK
--   Re-run the function definition from
--   V10__passwords_do_not_belong_in_the_audit_trail.sql verbatim; it is the
--   same body without the guard. Triggers keep pointing at the same function,
--   so nothing else has to be rebuilt.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.audit_trigger_fn()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
DECLARE
    v_table_id smallint;
    v_action_id smallint;
    v_changes jsonb := '{}'::jsonb;
    key text;
BEGIN

    -- ------------------------------------------------------------------
    -- The session switch (V71). Anything other than the exact string 'on'
    -- — unset, empty, 'off', a typo — means the trail is written, so a
    -- mistake fails towards MORE auditing, never less.
    -- ------------------------------------------------------------------
    IF current_setting('app.audit_off', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

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

        -- password_hash is excluded HERE and in the two branches below. The audit
        -- trail records what somebody decided, and nobody decides a hash; keeping
        -- them would build a history of every password every user has ever had,
        -- readable by everyone who may read the trail.
        v_changes := to_jsonb(NEW) - 'updated_at' - 'created_at'
                     - 'effective_work_code_category_id' - 'password_hash';

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

        v_changes := to_jsonb(OLD) - 'updated_at' - 'created_at'
                     - 'effective_work_code_category_id' - 'password_hash';

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

        FOR key IN SELECT jsonb_object_keys(to_jsonb(NEW)) LOOP
            IF key IN ('updated_at', 'created_at',
                       'effective_work_code_category_id', 'password_hash') THEN
                CONTINUE;
            END IF;

            IF to_jsonb(NEW) -> key IS DISTINCT FROM to_jsonb(OLD) -> key THEN
                v_changes := v_changes || jsonb_build_object(
                    key,
                    jsonb_build_object(
                        'old', to_jsonb(OLD) -> key,
                        'new', to_jsonb(NEW) -> key
                    )
                );
            END IF;
        END LOOP;

        -- A row touched without any audited column changing is not an event.
        IF v_changes = '{}'::jsonb THEN
            RETURN NEW;
        END IF;

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

    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION public.audit_trigger_fn() IS
    'Writes every audited change to audit_logs. Honours a per-session kill switch: SET app.audit_off = ''on'' (exactly that string) makes it skip the write for that session only; see V71. The application never sets it.';
