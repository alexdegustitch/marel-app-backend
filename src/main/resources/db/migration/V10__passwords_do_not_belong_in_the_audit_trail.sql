-- =============================================================================
-- Passwords do not belong in the audit trail
-- =============================================================================
-- WHAT CHANGES
--   audit_trigger_fn stops recording password_hash, and the hashes already
--   recorded are removed from audit_logs.
--
-- WHY
--   The trigger copies every column of every audited row into audit_logs.changes,
--   minus updated_at / created_at / effective_work_code_category_id. `users` is
--   audited, so password_hash is in there already: a history of bcrypt hashes,
--   readable by anyone who may read the audit trail.
--
--   An audit trail records what somebody DECIDED, and nobody decides a hash. It
--   is the one column in that table whose presence is pure liability — an old
--   hash is cheap to attack offline, and people reuse passwords.
--
--   It is a weakness today and it is about to become a much bigger one: the
--   release that follows this one lets people change their password, which
--   without this fix would accumulate every password every user has ever had.
--
-- MIGRATION IMPACT
--   · audit_trigger_fn is REPLACED, not dropped. The trigger definitions on all
--     thirty-odd audited tables keep pointing at the same function name and are
--     untouched; behaviour changes for one key on one table's payload.
--   · The UPDATE branch is otherwise identical to the original, including the
--     "a row touched with no audited column changed is not an event" rule.
--   · The scrub removes ONLY the password_hash key. Every other recorded change
--     survives exactly as it was, so no history of any other column is lost.
--   · Idempotent: replaying finds nothing left to scrub, and the verification
--     block below fails loudly if anything remains.
--   · Rollback: deliberately none. The scrubbed hashes are gone, which is the
--     point of the migration.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.audit_trigger_fn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

-- The hashes already in there. Only this key is removed; every other recorded
-- change survives untouched.
UPDATE public.audit_logs
SET changes = changes - 'password_hash'
WHERE changes ? 'password_hash';

DO $$
DECLARE
    v_left bigint;
BEGIN
    SELECT count(*) INTO v_left FROM public.audit_logs WHERE changes ? 'password_hash';
    IF v_left > 0 THEN
        RAISE EXCEPTION 'audit_logs still holds password_hash in % row(s)', v_left;
    END IF;
END $$;
