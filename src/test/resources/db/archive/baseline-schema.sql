--
-- PostgreSQL database dump
--


-- Dumped from database version 18.1 (Homebrew)
-- Dumped by pg_dump version 18.1 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: btree_gist; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;


--
-- Name: EXTENSION btree_gist; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: work_code_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.work_code_type AS ENUM (
    'smena',
    'odsustvo',
    'operacija',
    'odmor',
    'WORK',
    'ABSENCE'
);


--
-- Name: archive_product_and_operations(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.archive_product_and_operations() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
	-- Only when product is being deactivated
	if old.is_active = true and new.is_active = false then
		-- Archive all related operations
		update operations
		set
			is_active = false,
			archived_at = now(),
			archived_by_product = true
		where product_id = new.id and is_active = true;
	end if;

	return new;
end;
$$;


--
-- Name: archive_production_order_items(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.archive_production_order_items() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if new.is_active = false and old.is_active = true then

    update production_order_line_items
    set is_active = false,
	archived_at = now()
    where production_order_id = new.id
      and is_active = true;

  end if;

  return new;
end;
$$;


--
-- Name: audit_trigger_fn(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.audit_trigger_fn() RETURNS trigger
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
$$;


--
-- Name: block_manual_operation_reactivate(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.block_manual_operation_reactivate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if old.is_active = false 
     and new.is_active = true 
     and old.archived_by_product = false then
    raise exception
      'Ova operacija je arhivirana manuelno - kreirajte novu verziju operacije.';
  end if;

  return new;
end;
$$;


--
-- Name: clear_archived_at_on_reactivate(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.clear_archived_at_on_reactivate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if old.is_active = false and new.is_active = true then
    new.archived_at := null;
  end if;

  return new;
end;
$$;


--
-- Name: clear_archived_flag_on_manual_archive(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.clear_archived_flag_on_manual_archive() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if old.is_active = true and new.is_active = false then
    new.archived_by_product := false;
  end if;

  return new;
end;
$$;


--
-- Name: enforce_absence_within_shift(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_absence_within_shift() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM work_shifts ws
    WHERE ws.id = NEW.work_shift_id
      AND NEW.start_at >= ws.start_at
      AND NEW.end_at <= ws.end_at
  ) THEN
    RAISE EXCEPTION
      'Odsustvo (% - %) je van trajanja smene',
      NEW.start_at, NEW.end_at;
  END IF;

  RETURN NEW;
END;
$$;


--
-- Name: enforce_log_within_shift(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_log_within_shift() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
	if not exists(
		select 1
		from work_shifts ws
		where ws.id = new.work_shift_id
			and new.start_at >= ws.start_at
			and new.end_at <= ws.end_at
	) then
		raise exception
			'Vreme operacije (% - %) je izvan vremena trajanja smene',
			new.start_at, new.end_at;
	end if;
	return new;
end;
$$;


--
-- Name: fill_product_time_name(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.fill_product_time_name() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  select p.product_name || ' - ' || new.creation_date
  into new.name
  from products p
  where p.id = new.product_id;

  -- optional safety check
  if new.name is null then
    raise exception 'Proizvod % nije pronadjen', new.product_id;
  end if;

  return new;
end;
$$;


--
-- Name: operations_history_trigger_fn(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.operations_history_trigger_fn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_user_id bigint;
BEGIN
  -- Only log real changes
  IF NEW IS NOT DISTINCT FROM OLD THEN
    RETURN NEW;
  END IF;

  -- Get user_id from session (if provided)
  BEGIN
    v_user_id := current_setting('app.user_id', true)::bigint;
  EXCEPTION WHEN OTHERS THEN
    v_user_id := NULL;
  END;

  INSERT INTO operations_history (
    operation_id,
    changed_by,
    old_data,
    new_data
  )
  VALUES (
    OLD.id,
    v_user_id,
    to_jsonb(OLD),
    to_jsonb(NEW)
  );

  RETURN NEW;
END;
$$;


--
-- Name: revive_operations_when_product_reactivated(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.revive_operations_when_product_reactivated() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if old.is_active = false and new.is_active = true then
    update operations
    set
      is_active = true,
      archived_at = null,
      archived_by_product = false
    where product_id = new.id
      and archived_by_product = true;
  end if;

  return new;
end;
$$;


--
-- Name: set_archived_at_on_deactivate(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_archived_at_on_deactivate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
	if old.is_active = true and new.is_active = false then
		if new.archived_at is null then
			new.archived_at := now();
		end if;
	end if;

	return new;
end;
$$;


--
-- Name: set_sample_line_no(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_sample_line_no() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if new.line_no is null then
  	select coalesce(max(line_no), 0) + 1
	into new.line_no
	from sample_order_line_items
	where sample_order_id = new.sample_order_id
	for update;
  end if;

  return new;
end;
$$;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
	IF (to_jsonb(NEW) - 'updated_at') 
	   IS DISTINCT FROM 
	   (to_jsonb(OLD) - 'updated_at') THEN
	  NEW.updated_at := now();
	END IF;
	return new;
end;
$$;


--
-- Name: sync_user_account_status(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sync_user_account_status() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- account_status is authoritative on insert; is_active is derived.
        NEW.is_active := (NEW.account_status = 'ACTIVE');

        IF NEW.account_status = 'ACTIVE' AND NEW.activated_at IS NULL THEN
            NEW.activated_at := now();
        END IF;

        RETURN NEW;
    END IF;

    IF NEW.account_status IS DISTINCT FROM OLD.account_status THEN
        -- Explicit workflow transition wins over any is_active value supplied
        -- in the same statement.
        NEW.is_active := (NEW.account_status = 'ACTIVE');

        IF NEW.account_status = 'ACTIVE' AND NEW.activated_at IS NULL THEN
            NEW.activated_at := now();
        END IF;

    ELSIF NEW.is_active IS DISTINCT FROM OLD.is_active THEN
        -- Legacy path: only the boolean was touched. Reactivating means ACTIVE;
        -- deactivating a working account means SUSPENDED (an administrative act),
        -- never DECLINED — declining is a registration-review outcome and must go
        -- through the registration workflow.
        IF NEW.is_active THEN
            NEW.account_status := 'ACTIVE';
            IF NEW.activated_at IS NULL THEN
                NEW.activated_at := now();
            END IF;
        ELSIF OLD.account_status = 'ACTIVE' THEN
            NEW.account_status := 'SUSPENDED';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: trg_app_settings_versioning(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_app_settings_versioning() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  current_row app_settings;
BEGIN
  -- Lock current active row
  SELECT *
  INTO current_row
  FROM app_settings
  WHERE valid_until IS NULL
  ORDER BY valid_from DESC
  LIMIT 1
  FOR UPDATE;

  IF FOUND THEN
    -- same values? don't create duplicate
    IF current_row.max_efficiency_percent = NEW.max_efficiency_percent
       AND current_row.standard_bonus = NEW.standard_bonus
       AND current_row.meal_allowance = NEW.meal_allowance
       AND current_row.transport_allowance = NEW.transport_allowance THEN
      RAISE EXCEPTION
        'App settings already active with same values';
    END IF;

    -- close old version
    UPDATE app_settings
    SET valid_until = now()
    WHERE id = current_row.id;

    -- new version becomes active now
    NEW.valid_from := now();
  END IF;

  RETURN NEW;
END;
$$;


--
-- Name: trg_close_previous_employee_bonus(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_close_previous_employee_bonus() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
	-- Close current active bonus for this employee (if any)
	update employees_bonus_history
	set end_date = new.start_date
	where employee_id = new.employee_id
	  and end_date is null;

	return new;
end;
$$;


--
-- Name: trg_payroll_run_item_track_activity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_payroll_run_item_track_activity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_user_id_text text;
    v_user_id bigint;
    v_payroll_run_item_id bigint;
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

    -- Determine the payroll_run_item_id based on operation type
    IF TG_OP = 'DELETE' THEN
        v_payroll_run_item_id := OLD.id;
    ELSE
        v_payroll_run_item_id := NEW.id;
    END IF;

    -- Insert or update the activity record
    IF v_payroll_run_item_id IS NOT NULL THEN
        INSERT INTO public.employee_payroll_run_item_updates
            (payroll_run_item_id, user_id, last_activity_at)
        VALUES
            (v_payroll_run_item_id, v_user_id, NOW())
        ON CONFLICT (payroll_run_item_id, user_id)
        DO UPDATE SET
            last_activity_at = EXCLUDED.last_activity_at;
    END IF;

    RETURN CASE
        WHEN TG_OP = 'DELETE' THEN OLD
        ELSE NEW
    END;
END;
$$;


--
-- Name: trg_pmt_touch_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_pmt_touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


--
-- Name: trg_pmto_resolve_values(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_pmto_resolve_values() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT NEW.units_per_product_overridden THEN
        NEW.units_per_product_value := NEW.units_per_product_snapshot;
    END IF;

    IF NOT NEW.norm_overridden THEN
        NEW.norm_value := NEW.norm_snapshot;
    END IF;

    IF NOT NEW.norm_date_overridden THEN
        NEW.norm_date_value := NEW.norm_date_snapshot;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: trg_pmto_touch_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_pmto_touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


--
-- Name: trg_shift_update_activity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_shift_update_activity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.last_activity_at := now();
    RETURN NEW;
END;
$$;


--
-- Name: trg_work_logs_track_activity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_work_logs_track_activity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


--
-- Name: trg_work_shifts_track_activity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_work_shifts_track_activity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


--
-- Name: update_shift_activity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_shift_activity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE work_shifts
	SET last_activity_at = GREATEST(last_activity_at, now())
	WHERE id = COALESCE(NEW.work_shift_id, OLD.work_shift_id);

    RETURN COALESCE(NEW, OLD);
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: absence_compensations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.absence_compensations (
    id bigint NOT NULL,
    absence_record_id bigint NOT NULL,
    work_shift_id bigint NOT NULL,
    compensated_minutes integer NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_comp_minutes_positive CHECK ((compensated_minutes > 0))
);


--
-- Name: absence_compensations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.absence_compensations ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.absence_compensations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: absence_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.absence_records (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    work_shift_id bigint NOT NULL,
    work_code_category_id bigint NOT NULL,
    note text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    absence_minutes integer NOT NULL,
    norm_multiplier_snapshot numeric(10,2) NOT NULL,
    paid_minutes integer NOT NULL,
    start_at timestamp with time zone,
    end_at timestamp with time zone,
    CONSTRAINT chk_absence_minutes_positive CHECK ((absence_minutes >= 0)),
    CONSTRAINT chk_absence_records_valid_note_not_empty CHECK (((note IS NULL) OR (length(TRIM(BOTH FROM note)) > 0))),
    CONSTRAINT chk_multiplier_valid CHECK ((norm_multiplier_snapshot >= (0)::numeric)),
    CONSTRAINT chk_paid_minutes_valid CHECK (((paid_minutes >= 0) AND (paid_minutes <= absence_minutes)))
);


--
-- Name: absence_records_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.absence_records ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.absence_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: app_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_settings (
    id bigint NOT NULL,
    setting_key character varying(255) NOT NULL,
    value_type character varying(255) DEFAULT 'number'::character varying NOT NULL,
    description character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    valid_from timestamp with time zone DEFAULT now() NOT NULL,
    valid_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    setting_value_text character varying(255),
    setting_value_numeric numeric(38,2),
    setting_value_boolean boolean,
    unit character varying(255),
    affects_payroll boolean DEFAULT false NOT NULL,
    display_text character varying(255),
    CONSTRAINT chk_app_settings_key_not_empty CHECK ((length(TRIM(BOTH FROM setting_key)) > 0)),
    CONSTRAINT chk_app_settings_numeric_non_negative CHECK (((setting_value_numeric IS NULL) OR (setting_value_numeric >= (0)::numeric))),
    CONSTRAINT chk_app_settings_validity CHECK (((valid_until IS NULL) OR (valid_until > valid_from))),
    CONSTRAINT chk_app_settings_value_type_enum CHECK (((value_type)::text = ANY (ARRAY[('text'::character varying)::text, ('number'::character varying)::text, ('boolean'::character varying)::text]))),
    CONSTRAINT chk_app_settings_value_type_match CHECK (((((value_type)::text = 'text'::text) AND (setting_value_text IS NOT NULL) AND (setting_value_numeric IS NULL) AND (setting_value_boolean IS NULL)) OR (((value_type)::text = 'number'::text) AND (setting_value_numeric IS NOT NULL) AND (setting_value_text IS NULL) AND (setting_value_boolean IS NULL)) OR (((value_type)::text = 'boolean'::text) AND (setting_value_boolean IS NOT NULL) AND (setting_value_text IS NULL) AND (setting_value_numeric IS NULL))))
);


--
-- Name: app_settings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.app_settings ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.app_settings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_actions (
    id smallint NOT NULL,
    action_name character varying(255) NOT NULL
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id bigint NOT NULL,
    user_id bigint,
    record_id bigint,
    change_time timestamp with time zone DEFAULT now() NOT NULL,
    table_id smallint NOT NULL,
    action_id smallint NOT NULL,
    changes jsonb
);


--
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_logs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_tables; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_tables (
    id smallint NOT NULL,
    table_name character varying(255) NOT NULL
);


--
-- Name: audit_tables_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_tables ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.audit_tables_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: bonus_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bonus_categories (
    id bigint NOT NULL,
    category_no character varying(255) NOT NULL,
    category_name character varying(255) NOT NULL,
    bonus_amount numeric(10,2) NOT NULL,
    min_hours numeric(5,2) NOT NULL,
    description character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    valid_from date DEFAULT CURRENT_DATE,
    valid_until date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_bonus_categories_bonus_amount CHECK ((bonus_amount >= (0)::numeric)),
    CONSTRAINT chk_bonus_categories_min_hours CHECK ((min_hours >= (0)::numeric)),
    CONSTRAINT chk_bonus_categories_name_not_empty CHECK ((length(TRIM(BOTH FROM category_name)) > 0)),
    CONSTRAINT chk_bonus_categories_no_not_empty CHECK ((length(TRIM(BOTH FROM category_no)) > 0)),
    CONSTRAINT chk_bonus_categories_no_reactivate CHECK ((NOT ((archived_at IS NOT NULL) AND (is_active = true)))),
    CONSTRAINT chk_bonus_categories_validity CHECK (((valid_until IS NULL) OR (valid_until >= valid_from)))
);


--
-- Name: bonus_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.bonus_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.bonus_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: bonus_eligibility_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bonus_eligibility_rules (
    id bigint NOT NULL,
    period date NOT NULL,
    min_num_hours integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    saturday_count integer,
    bonus_value numeric(12,2),
    note character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_bonus_eligibility_rules_bonus_value CHECK (((bonus_value IS NULL) OR (bonus_value >= (0)::numeric))),
    CONSTRAINT chk_bonus_eligibility_rules_hours CHECK ((min_num_hours >= 0)),
    CONSTRAINT chk_bonus_eligibility_rules_period_month CHECK ((date_trunc('month'::text, (period)::timestamp with time zone) = period)),
    CONSTRAINT chk_bonus_eligibility_rules_saturday_count CHECK (((saturday_count IS NULL) OR ((saturday_count >= 0) AND (saturday_count <= 5))))
);


--
-- Name: bonus_eligibility_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.bonus_eligibility_rules ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.bonus_eligibility_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: bonus_min_hours_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bonus_min_hours_rules (
    id bigint NOT NULL,
    period date NOT NULL,
    min_num_hours integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_bonus_min_hours_rules_archived_after_created CHECK (((archived_at IS NULL) OR (archived_at >= created_at))),
    CONSTRAINT chk_bonus_min_hours_rules_min_num_hours_positive CHECK ((min_num_hours > 0)),
    CONSTRAINT chk_bonus_min_hours_rules_period_is_month_start CHECK ((period = (date_trunc('month'::text, (period)::timestamp with time zone))::date)),
    CONSTRAINT chk_bonus_min_hours_rules_updated_after_created CHECK (((updated_at IS NULL) OR (updated_at >= created_at)))
);


--
-- Name: bonus_min_hours_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.bonus_min_hours_rules ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.bonus_min_hours_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: daily_report_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.daily_report_categories (
    id bigint NOT NULL,
    daily_report_id bigint NOT NULL,
    work_code_category_id bigint NOT NULL,
    total_minutes integer DEFAULT 0 NOT NULL,
    total_quantity integer DEFAULT 0 NOT NULL,
    total_scrap integer DEFAULT 0 NOT NULL,
    total_weighted_norm_minutes numeric(38,2) DEFAULT 0 NOT NULL,
    source_type character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    total_paid_minutes integer DEFAULT 0 NOT NULL,
    performance_coefficient numeric(38,2) DEFAULT 0 NOT NULL,
    approved_performance_coefficient numeric(38,2) DEFAULT 0 CONSTRAINT daily_report_categories_approved_performance_coefficie_not_null NOT NULL
);


--
-- Name: daily_report_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.daily_report_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.daily_report_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: daily_report_recalc_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.daily_report_recalc_queue (
    id bigint NOT NULL,
    employee_id bigint,
    work_date date,
    work_shift_id bigint NOT NULL,
    reason character varying(255),
    status character varying(255) DEFAULT 'PENDING'::text,
    requested_at timestamp with time zone DEFAULT now(),
    processed_at timestamp with time zone,
    retry_count integer DEFAULT 0,
    error_message character varying(255),
    locked_at timestamp without time zone,
    locked_by character varying(255),
    version integer,
    claimed_at timestamp(6) with time zone,
    claimed_by character varying(255),
    last_error character varying(255),
    last_stuck_at timestamp(6) with time zone,
    stuck_count integer,
    CONSTRAINT chk_daily_queue_status CHECK (((status)::text = ANY (ARRAY['PENDING'::text, 'IN_PROGRESS'::text, 'DONE'::text, 'FAILED'::text])))
);


--
-- Name: daily_report_recalc_queue_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.daily_report_recalc_queue ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.daily_report_recalc_queue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: daily_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.daily_reports (
    id bigint NOT NULL,
    employee_id bigint,
    work_date date,
    work_shift_id bigint NOT NULL,
    total_shift_minutes integer DEFAULT 0,
    total_work_minutes integer DEFAULT 0,
    total_compensated_minutes integer DEFAULT 0,
    total_approved_minutes integer DEFAULT 0,
    total_quantity integer DEFAULT 0,
    total_scrap integer DEFAULT 0,
    total_weighted_norm_minutes numeric(38,2) DEFAULT 0,
    performance_rate numeric(38,2),
    approved_performance_rate numeric(38,2),
    performance_coefficient numeric(38,2),
    calc_version integer DEFAULT 1 NOT NULL,
    last_recalculated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    version integer NOT NULL,
    approved_performance_coefficient numeric(38,2),
    total_absence_paid_minutes integer,
    total_absence_unpaid_minutes integer,
    total_sick_leave_paid_minutes integer,
    total_sick_leave_unpaid_minutes integer,
    is_meal_allowed boolean DEFAULT false NOT NULL,
    bonus_eligible_minutes integer DEFAULT 0 NOT NULL,
    meals_count integer DEFAULT 0 NOT NULL
);


--
-- Name: daily_reports_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.daily_reports ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.daily_reports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: departments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.departments (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_departments_name CHECK ((length(TRIM(BOTH FROM name)) > 0))
);


--
-- Name: departments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.departments ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.departments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_payroll_run_item_updates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_payroll_run_item_updates (
    id bigint NOT NULL,
    last_activity_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    payroll_run_item_id bigint NOT NULL,
    user_id bigint NOT NULL
);


--
-- Name: employee_payroll_run_item_updates_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_payroll_run_item_updates ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_payroll_run_item_updates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_record_updates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_record_updates (
    id bigint NOT NULL,
    last_activity_at timestamp(6) with time zone NOT NULL,
    employee_record_id bigint NOT NULL,
    user_id bigint NOT NULL
);


--
-- Name: employee_record_updates_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_record_updates ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_record_updates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_records (
    id bigint NOT NULL,
    is_active boolean NOT NULL,
    archived_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    end_date date,
    start_date date NOT NULL,
    updated_at timestamp(6) with time zone,
    employee_id bigint NOT NULL
);


--
-- Name: employee_records_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_records ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employees (
    id bigint NOT NULL,
    department_id bigint NOT NULL,
    full_name character varying(255) NOT NULL,
    employee_no character varying(255) NOT NULL,
    employment_start_date date NOT NULL,
    employment_end_date date,
    notes text,
    is_foreigner boolean NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    norm_grace_days integer DEFAULT 30 NOT NULL,
    probation_end_date date GENERATED ALWAYS AS ((employment_start_date + norm_grace_days)) STORED,
    transport_allowance_rsd numeric(10,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    mobile_phone character varying(50),
    hourly_rate numeric(10,2),
    default_work_category_id bigint,
    works_in_commercial boolean DEFAULT false NOT NULL,
    transport_allowance_mode character varying(20) DEFAULT 'AUTO'::character varying NOT NULL,
    CONSTRAINT chk_employees_employee_no CHECK ((length(TRIM(BOTH FROM employee_no)) > 0)),
    CONSTRAINT chk_employees_employment_range CHECK (((employment_end_date IS NULL) OR (employment_end_date >= employment_start_date))),
    CONSTRAINT chk_employees_full_name CHECK ((length(TRIM(BOTH FROM full_name)) > 0)),
    CONSTRAINT chk_employees_hourly_rate_positive CHECK (((hourly_rate IS NULL) OR (hourly_rate >= (0)::numeric))),
    CONSTRAINT chk_employees_travel_compensation_positive CHECK (((transport_allowance_rsd IS NULL) OR (transport_allowance_rsd >= (0)::numeric)))
);


--
-- Name: employees_bonus_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employees_bonus_history (
    employee_id bigint NOT NULL,
    bonus_category_id bigint NOT NULL,
    start_date date NOT NULL,
    end_date date,
    changed_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint NOT NULL,
    CONSTRAINT chk_employees_bonus_history_range CHECK (((end_date IS NULL) OR (end_date >= start_date)))
);


--
-- Name: employees_bonus_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employees_bonus_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employees_bonus_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employees_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employees ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.employees_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: livac_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.livac_categories (
    id bigint NOT NULL,
    category_no text NOT NULL,
    category_name text NOT NULL,
    rsd_per_hour numeric(10,2) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    valid_from date DEFAULT CURRENT_DATE,
    valid_until date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_livac_categories_hourly_rate CHECK ((rsd_per_hour >= (0)::numeric)),
    CONSTRAINT chk_livac_categories_name_not_empty CHECK ((length(TRIM(BOTH FROM category_name)) > 0)),
    CONSTRAINT chk_livac_categories_no_not_empty CHECK ((length(TRIM(BOTH FROM category_no)) > 0)),
    CONSTRAINT chk_livac_categories_no_reactivate CHECK ((NOT ((archived_at IS NOT NULL) AND (is_active = true)))),
    CONSTRAINT chk_livac_categories_validity CHECK (((valid_until IS NULL) OR (valid_until >= valid_from)))
);


--
-- Name: livac_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.livac_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.livac_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: mailing_list_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mailing_list_access (
    mailing_list_id bigint NOT NULL,
    user_id bigint NOT NULL,
    granted_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE mailing_list_access; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mailing_list_access IS 'Explicit per-user grants for SHARED mailing lists. Cascades with the list because a grant has no meaning without it.';


--
-- Name: mailing_list_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mailing_list_members (
    id bigint NOT NULL,
    mailing_list_id bigint NOT NULL,
    user_id bigint,
    external_email character varying(320),
    display_name character varying(150),
    created_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_at timestamp with time zone,
    CONSTRAINT chk_mailing_list_members_display_name CHECK (((display_name IS NULL) OR (length(TRIM(BOTH FROM display_name)) > 0))),
    CONSTRAINT chk_mailing_list_members_exactly_one_source CHECK ((((user_id IS NOT NULL) AND (external_email IS NULL)) OR ((user_id IS NULL) AND (external_email IS NOT NULL)))),
    CONSTRAINT chk_mailing_list_members_external_email CHECK (((external_email IS NULL) OR (((external_email)::text = lower(TRIM(BOTH FROM external_email))) AND ((external_email)::text ~~ '%_@_%._%'::text) AND ((external_email)::text !~ '[[:space:]]'::text))))
);


--
-- Name: TABLE mailing_list_members; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mailing_list_members IS 'Members of a mailing list: either an application user or an external email address. Removal is an archive (archived_at), never a delete.';


--
-- Name: COLUMN mailing_list_members.external_email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mailing_list_members.external_email IS 'Normalized lower-case email for a non-application recipient. Mutually exclusive with user_id.';


--
-- Name: mailing_list_members_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.mailing_list_members ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.mailing_list_members_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: mailing_lists; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mailing_lists (
    id bigint NOT NULL,
    name character varying(150) NOT NULL,
    description character varying(1000),
    owner_user_id bigint NOT NULL,
    visibility character varying(20) DEFAULT 'PRIVATE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_mailing_lists_name CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT chk_mailing_lists_visibility CHECK (((visibility)::text = ANY (ARRAY[('PRIVATE'::character varying)::text, ('SHARED'::character varying)::text, ('GLOBAL'::character varying)::text])))
);


--
-- Name: TABLE mailing_lists; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mailing_lists IS 'Reusable recipient list owned by a user. Archived (archived_at) rather than deleted so production-order history stays intact.';


--
-- Name: mailing_lists_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.mailing_lists ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.mailing_lists_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: manufacturing_product_times; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.manufacturing_product_times (
    id bigint NOT NULL,
    product_id bigint NOT NULL,
    name text NOT NULL,
    creation_date date DEFAULT CURRENT_DATE NOT NULL,
    total_production_time_seconds integer CONSTRAINT manufacturing_product_times_total_production_time_seco_not_null NOT NULL,
    units_per_hour numeric(10,2) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_manufacturing_product_times_name CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT chk_manufacturing_product_times_total_production_time_seconds CHECK ((total_production_time_seconds > 0)),
    CONSTRAINT chk_manufacturing_product_times_units_per_hour CHECK ((units_per_hour > (0)::numeric))
);


--
-- Name: manufacturing_product_times_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.manufacturing_product_times ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.manufacturing_product_times_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: manufacturing_time_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.manufacturing_time_operations (
    id bigint NOT NULL,
    product_time_id bigint NOT NULL,
    operation_id bigint NOT NULL,
    units_per_assembly integer NOT NULL,
    units_per_hour numeric(10,2) NOT NULL,
    operation_time_seconds integer NOT NULL,
    norm_date date NOT NULL,
    note text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_manufacturing_time_operations_operation_time_seconds CHECK ((operation_time_seconds > 0)),
    CONSTRAINT chk_manufacturing_time_operations_units_per_assembly CHECK ((units_per_assembly > 0)),
    CONSTRAINT chk_manufacturing_time_operations_units_per_hour CHECK ((units_per_hour > (0)::numeric))
);


--
-- Name: manufacturing_time_operations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.manufacturing_time_operations ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.manufacturing_time_operations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: manufacturing_time_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.manufacturing_time_requests (
    id bigint NOT NULL,
    product_id bigint NOT NULL,
    created_by bigint NOT NULL,
    request_type character varying(20) NOT NULL,
    description character varying(2000) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    assigned_to bigint,
    processed_by bigint,
    processed_at timestamp with time zone,
    decision_note character varying(2000),
    target_manufacturing_time_id bigint,
    cancelled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_manufacturing_time_requests_assignment_state CHECK (((((status)::text = 'IN_REVIEW'::text) AND (assigned_to IS NOT NULL)) OR (((status)::text = 'PENDING'::text) AND (assigned_to IS NULL)) OR ((status)::text = ANY (ARRAY[('COMPLETED'::character varying)::text, ('DECLINED'::character varying)::text, ('CANCELLED'::character varying)::text])))),
    CONSTRAINT chk_manufacturing_time_requests_decision_note CHECK (((decision_note IS NULL) OR (length(TRIM(BOTH FROM decision_note)) > 0))),
    CONSTRAINT chk_manufacturing_time_requests_description CHECK ((length(TRIM(BOTH FROM description)) > 0)),
    CONSTRAINT chk_manufacturing_time_requests_processing_state CHECK (((((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('IN_REVIEW'::character varying)::text])) AND (processed_by IS NULL) AND (processed_at IS NULL) AND (cancelled_at IS NULL)) OR (((status)::text = ANY (ARRAY[('COMPLETED'::character varying)::text, ('DECLINED'::character varying)::text])) AND (processed_by IS NOT NULL) AND (processed_at IS NOT NULL) AND (cancelled_at IS NULL)) OR (((status)::text = 'CANCELLED'::text) AND (cancelled_at IS NOT NULL) AND (processed_by IS NULL) AND (processed_at IS NULL)))),
    CONSTRAINT chk_manufacturing_time_requests_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('COMPLETED'::character varying)::text, ('DECLINED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT chk_manufacturing_time_requests_target_required CHECK (((((request_type)::text = 'CREATE'::text) AND (target_manufacturing_time_id IS NULL)) OR (((request_type)::text <> 'CREATE'::text) AND (target_manufacturing_time_id IS NOT NULL)))),
    CONSTRAINT chk_manufacturing_time_requests_type CHECK (((request_type)::text = ANY (ARRAY[('CREATE'::character varying)::text, ('UPDATE'::character varying)::text, ('RECALCULATE'::character varying)::text, ('DEACTIVATE'::character varying)::text])))
);


--
-- Name: TABLE manufacturing_time_requests; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.manufacturing_time_requests IS 'A user request to create, update, recalculate or deactivate a product manufacturing time. Append-only history; never deleted.';


--
-- Name: COLUMN manufacturing_time_requests.created_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.manufacturing_time_requests.created_by IS 'Who submitted the request.';


--
-- Name: COLUMN manufacturing_time_requests.assigned_to; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.manufacturing_time_requests.assigned_to IS 'Who currently owns the request (set when it moves to IN_REVIEW).';


--
-- Name: COLUMN manufacturing_time_requests.processed_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.manufacturing_time_requests.processed_by IS 'Who completed or declined it. Business state, not audit metadata.';


--
-- Name: COLUMN manufacturing_time_requests.target_manufacturing_time_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.manufacturing_time_requests.target_manufacturing_time_id IS 'The existing manufacturing-time record the request acts on. NULL for CREATE.';


--
-- Name: manufacturing_time_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.manufacturing_time_requests ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.manufacturing_time_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: monthly_report_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monthly_report_categories (
    id bigint NOT NULL,
    monthly_report_id bigint NOT NULL,
    work_code_category_id bigint NOT NULL,
    total_minutes integer DEFAULT 0 NOT NULL,
    total_paid_minutes integer DEFAULT 0 NOT NULL,
    total_quantity integer DEFAULT 0 NOT NULL,
    total_scrap integer DEFAULT 0 NOT NULL,
    source_type character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    total_approved_minutes numeric(38,2),
    total_weighted_norm_minutes numeric(38,2) DEFAULT 0 NOT NULL
);


--
-- Name: monthly_report_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.monthly_report_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.monthly_report_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: monthly_report_recalc_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monthly_report_recalc_queue (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    report_year integer NOT NULL,
    report_month integer NOT NULL,
    reason character varying(255),
    status character varying(255) DEFAULT 'PENDING'::text,
    requested_at timestamp with time zone DEFAULT now(),
    processed_at timestamp with time zone,
    retry_count integer DEFAULT 0,
    error_message character varying(255),
    locked_at timestamp without time zone,
    locked_by character varying(255),
    report_date date,
    version integer,
    claimed_at timestamp(6) with time zone,
    claimed_by character varying(255),
    last_error character varying(255),
    last_stuck_at timestamp(6) with time zone,
    stuck_count integer,
    CONSTRAINT chk_monthly_queue_status CHECK (((status)::text = ANY (ARRAY['PENDING'::text, 'IN_PROGRESS'::text, 'DONE'::text, 'FAILED'::text])))
);


--
-- Name: monthly_report_recalc_queue_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.monthly_report_recalc_queue ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.monthly_report_recalc_queue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: monthly_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monthly_reports (
    id bigint NOT NULL,
    total_shift_minutes integer DEFAULT 0 NOT NULL,
    total_work_minutes integer DEFAULT 0 NOT NULL,
    total_approved_minutes integer DEFAULT 0 NOT NULL,
    total_quantity integer DEFAULT 0 NOT NULL,
    total_scrap integer DEFAULT 0 NOT NULL,
    performance_rate numeric(38,2),
    approved_performance_rate numeric(38,2),
    performance_coefficient numeric(38,2),
    meal_allowance_num integer DEFAULT 0,
    status character varying(255) DEFAULT 'OPEN'::text,
    calc_version integer DEFAULT 1,
    last_recalculated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    version integer DEFAULT 0 NOT NULL,
    approved_performance_coefficient numeric(38,2),
    start_date date NOT NULL,
    end_date date NOT NULL,
    total_absence_paid_minutes integer DEFAULT 0 NOT NULL,
    total_absence_unpaid_minutes integer DEFAULT 0 NOT NULL,
    total_sick_leave_paid_minutes integer DEFAULT 0 NOT NULL,
    total_sick_leave_unpaid_minutes integer DEFAULT 0 NOT NULL,
    total_weighted_norm_minutes numeric(38,2) DEFAULT 0 CONSTRAINT monthly_reports_total_weighted_norm_minutes_not_null1 NOT NULL,
    employee_record_id bigint NOT NULL,
    total_absence_minutes integer DEFAULT 0 NOT NULL,
    total_sick_leave_minutes integer DEFAULT 0 NOT NULL
);


--
-- Name: monthly_reports_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.monthly_reports ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.monthly_reports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: notification_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_deliveries (
    id bigint NOT NULL,
    notification_event_id bigint NOT NULL,
    channel character varying(20) NOT NULL,
    recipient_user_id bigint,
    recipient_email character varying(320),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone,
    last_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT chk_notification_deliveries_attempt_count CHECK ((attempt_count >= 0)),
    CONSTRAINT chk_notification_deliveries_channel CHECK (((channel)::text = ANY (ARRAY[('IN_APP'::character varying)::text, ('EMAIL'::character varying)::text]))),
    CONSTRAINT chk_notification_deliveries_email CHECK (((recipient_email IS NULL) OR (((recipient_email)::text = lower(TRIM(BOTH FROM recipient_email))) AND ((recipient_email)::text ~~ '%_@_%._%'::text) AND ((recipient_email)::text !~ '[[:space:]]'::text)))),
    CONSTRAINT chk_notification_deliveries_sent_at CHECK (((((status)::text = 'SENT'::text) AND (sent_at IS NOT NULL)) OR (((status)::text <> 'SENT'::text) AND (sent_at IS NULL)))),
    CONSTRAINT chk_notification_deliveries_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('SENT'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT chk_notification_deliveries_target CHECK (((((channel)::text = 'EMAIL'::text) AND (recipient_email IS NOT NULL)) OR (((channel)::text = 'IN_APP'::text) AND (recipient_user_id IS NOT NULL))))
);


--
-- Name: TABLE notification_deliveries; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.notification_deliveries IS 'Per-channel delivery attempt for a notification event, with retry state. Rows are never deleted in normal operation.';


--
-- Name: notification_deliveries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.notification_deliveries ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.notification_deliveries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: notification_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_events (
    id bigint NOT NULL,
    outbox_event_id bigint,
    type character varying(60) NOT NULL,
    actor_user_id bigint,
    entity_type character varying(60) NOT NULL,
    entity_id bigint NOT NULL,
    title character varying(200) NOT NULL,
    message character varying(2000) NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_notification_events_message CHECK ((length(TRIM(BOTH FROM message)) > 0)),
    CONSTRAINT chk_notification_events_title CHECK ((length(TRIM(BOTH FROM title)) > 0))
);


--
-- Name: TABLE notification_events; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.notification_events IS 'One row per persistent business event. Fanned out to users via user_notifications and to channels via notification_deliveries.';


--
-- Name: notification_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.notification_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.notification_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operations (
    id bigint NOT NULL,
    product_id bigint NOT NULL,
    op_name character varying(255) NOT NULL,
    description character varying(255),
    min_norm integer,
    max_norm integer,
    units_per_product integer,
    norm_date date,
    is_temporary boolean DEFAULT false NOT NULL,
    archived_by_product boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    norm_required boolean DEFAULT true NOT NULL,
    work_code_category_id bigint,
    CONSTRAINT chk_operations_no_reactivate CHECK ((NOT ((archived_at IS NOT NULL) AND (is_active = true)))),
    CONSTRAINT chk_operations_norm_date_logic CHECK (((is_temporary = true) OR (norm_date IS NULL) OR (is_temporary = false) OR (norm_date IS NOT NULL))),
    CONSTRAINT chk_operations_norm_range CHECK (((min_norm IS NULL) OR (max_norm IS NULL) OR (min_norm <= max_norm))),
    CONSTRAINT chk_operations_norm_required_valid CHECK (((norm_required = false) OR ((min_norm IS NOT NULL) AND (max_norm IS NOT NULL) AND (min_norm > 0) AND (max_norm > 0) AND (min_norm <= max_norm)))),
    CONSTRAINT chk_operations_units_positive CHECK (((units_per_product IS NULL) OR (units_per_product > 0)))
);


--
-- Name: operations_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operations_history (
    id bigint NOT NULL,
    operation_id bigint NOT NULL,
    changed_by bigint,
    change_time timestamp with time zone DEFAULT now() NOT NULL,
    old_data jsonb,
    new_data jsonb
);


--
-- Name: operations_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.operations_history ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.operations_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: operations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.operations ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.operations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_events (
    id bigint NOT NULL,
    event_type character varying(60) NOT NULL,
    aggregate_type character varying(60) NOT NULL,
    aggregate_id bigint NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_at timestamp with time zone,
    CONSTRAINT chk_outbox_events_attempt_count CHECK ((attempt_count >= 0)),
    CONSTRAINT chk_outbox_events_processed_at CHECK (((((status)::text = 'PROCESSED'::text) AND (processed_at IS NOT NULL)) OR (((status)::text <> 'PROCESSED'::text) AND (processed_at IS NULL)))),
    CONSTRAINT chk_outbox_events_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('PROCESSED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: TABLE outbox_events; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.outbox_events IS 'Transactional outbox. Written in the same transaction as the business change; drained by OutboxEventWorker with FOR UPDATE SKIP LOCKED.';


--
-- Name: outbox_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.outbox_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.outbox_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_adjustment_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_adjustment_categories (
    id bigint NOT NULL,
    code character varying(255) CONSTRAINT payroll_adjustment_categories_category_no_not_null NOT NULL,
    name character varying(255) NOT NULL,
    input_type character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now(),
    section_code character varying(255) DEFAULT 'ADDITIONS'::text NOT NULL,
    section_order integer DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    impact_code character varying(255) DEFAULT 'GROSS_PLUS'::text NOT NULL,
    is_manual boolean DEFAULT true NOT NULL,
    allow_negative boolean DEFAULT false NOT NULL,
    visible_in_ui boolean DEFAULT true NOT NULL,
    visible_in_pdf boolean DEFAULT true NOT NULL,
    allow_override boolean DEFAULT false NOT NULL,
    override_target character varying(255) DEFAULT 'AMOUNT'::text NOT NULL,
    calculation_key character varying(255),
    archived_at timestamp with time zone,
    show_name boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_payroll_adj_impact CHECK (((impact_code)::text = ANY (ARRAY['GROSS_PLUS'::text, 'DEDUCTION_MINUS'::text, 'PAYMENT_MINUS'::text, 'BALANCE_PLUS'::text, 'INFO_ONLY'::text]))),
    CONSTRAINT chk_payroll_adj_override_target CHECK (((override_target)::text = ANY (ARRAY['AMOUNT'::text, 'UNIT_AMOUNT'::text, 'COMPONENTS'::text])))
);


--
-- Name: payroll_adjustment_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_adjustment_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payroll_adjustment_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_adjustments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_adjustments (
    id bigint NOT NULL,
    payroll_run_item_id bigint NOT NULL,
    payroll_adjustment_category_id bigint NOT NULL,
    amount numeric(38,2) NOT NULL,
    note character varying(255),
    is_applied boolean DEFAULT true NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    system_quantity numeric(38,2),
    quantity numeric(38,2),
    system_unit_amount numeric(38,2),
    unit_amount numeric(38,2),
    system_amount numeric(38,2) DEFAULT 0 NOT NULL,
    is_overridden boolean DEFAULT false NOT NULL,
    edited_by bigint,
    edited_at timestamp with time zone,
    CONSTRAINT chk_payroll_adjustments_note_not_empty CHECK (((note IS NULL) OR (length(TRIM(BOTH FROM note)) > 0))),
    CONSTRAINT chk_payroll_adjustments_reason_not_empty CHECK ((length(TRIM(BOTH FROM note)) > 0))
);


--
-- Name: payroll_adjustments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_adjustments ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payroll_adjustments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_run_item_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_run_item_categories (
    id bigint NOT NULL,
    payroll_run_item_id bigint NOT NULL,
    work_code_category_id bigint NOT NULL,
    source_type character varying(255) NOT NULL,
    total_minutes integer DEFAULT 0 NOT NULL,
    total_paid_minutes integer DEFAULT 0 NOT NULL,
    total_quantity integer DEFAULT 0 NOT NULL,
    total_scrap integer DEFAULT 0 NOT NULL,
    weighted_norm_minutes numeric(38,2) DEFAULT 0 NOT NULL,
    performance_coefficient numeric(38,2),
    category_coefficient_snapshot numeric(38,2) DEFAULT 1.00 CONSTRAINT payroll_run_item_categories_category_coefficient_snaps_not_null NOT NULL,
    effective_minutes numeric(38,2) DEFAULT 0.00 NOT NULL,
    hourly_rate numeric(38,2) DEFAULT 0.00 NOT NULL,
    amount numeric(38,2) DEFAULT 0.00 NOT NULL,
    category_is_paid_snapshot boolean,
    category_affects_norm_snapshot boolean,
    category_affects_bonus_snapshot boolean,
    bonus_amount numeric(38,2),
    note character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_pric_non_negative CHECK (((total_minutes >= 0) AND (total_paid_minutes >= 0) AND (total_quantity >= 0) AND (total_scrap >= 0) AND (weighted_norm_minutes >= (0)::numeric) AND (bonus_amount >= (0)::numeric) AND (category_coefficient_snapshot >= (0)::numeric) AND (performance_coefficient >= (0)::numeric) AND (effective_minutes >= (0)::numeric) AND (amount >= (0)::numeric))),
    CONSTRAINT chk_pric_note_not_empty CHECK (((note IS NULL) OR (length(TRIM(BOTH FROM note)) > 0))),
    CONSTRAINT chk_pric_source_type CHECK (((source_type)::text = ANY (ARRAY[('WORK'::character varying)::text, ('ABSENCE'::character varying)::text, ('COMPENSATION'::character varying)::text, ('SICK_LEAVE'::character varying)::text])))
);


--
-- Name: payroll_run_item_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_run_item_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payroll_run_item_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_run_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_run_items (
    id bigint NOT NULL,
    payroll_run_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    monthly_report_id bigint,
    total_shift_minutes integer DEFAULT 0 NOT NULL,
    total_work_minutes integer DEFAULT 0 NOT NULL,
    total_absence_minutes integer DEFAULT 0 NOT NULL,
    total_paid_absence_minutes integer DEFAULT 0 NOT NULL,
    total_unpaid_absence_minutes integer DEFAULT 0 NOT NULL,
    total_compensated_minutes integer DEFAULT 0 NOT NULL,
    total_approved_minutes integer DEFAULT 0 NOT NULL,
    total_quantity integer DEFAULT 0 NOT NULL,
    total_scrap integer DEFAULT 0 NOT NULL,
    total_effective_minutes numeric(38,2) DEFAULT 0 CONSTRAINT payroll_run_items_total_weighted_norm_minutes_not_null NOT NULL,
    performance_rate numeric(38,2),
    approved_performance_rate numeric(38,2),
    performance_coefficient numeric(38,2),
    status character varying(255) DEFAULT 'DRAFT'::character varying NOT NULL,
    hourly_rate numeric(38,2) DEFAULT 0 NOT NULL,
    adjustment_amount numeric(38,2) DEFAULT 0 NOT NULL,
    total_gross_earnings numeric(38,2) DEFAULT 0,
    total_net_earnings numeric(38,2) DEFAULT 0,
    currency_code character varying(255) DEFAULT 'RSD'::character varying NOT NULL,
    calc_version integer DEFAULT 1 NOT NULL,
    note character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    total_work_days integer DEFAULT 0 NOT NULL,
    total_paid_days integer DEFAULT 0 NOT NULL,
    total_absence_days integer DEFAULT 0 NOT NULL,
    based_on_version integer,
    last_calculated_at timestamp without time zone,
    locked_at timestamp with time zone,
    locked_by bigint,
    period date,
    hourly_rate_system numeric(38,2) DEFAULT 0 NOT NULL,
    hourly_rate_overridden boolean DEFAULT false NOT NULL,
    base_bonus_amount_system numeric(38,2) DEFAULT 0 NOT NULL,
    base_bonus_amount numeric(38,2) DEFAULT 0 NOT NULL,
    base_bonus_amount_overridden boolean DEFAULT false NOT NULL,
    bonus_correction_amount_system numeric(38,2) DEFAULT 0 NOT NULL,
    bonus_correction_amount numeric(38,2) DEFAULT 0 NOT NULL,
    bonus_correction_amount_overridden boolean DEFAULT false NOT NULL,
    total_bonus_amount_system numeric(38,2) DEFAULT 0 NOT NULL,
    total_bonus_amount numeric(38,2) DEFAULT 0 NOT NULL,
    total_bonus_amount_overridden boolean DEFAULT false NOT NULL,
    previously_paid_amount numeric(38,2) DEFAULT 0 NOT NULL,
    previous_net_payable_amount numeric(38,2) DEFAULT 0 CONSTRAINT payroll_run_items_previous_balance_amount_not_null NOT NULL,
    current_balance_amount numeric(38,2) DEFAULT 0 NOT NULL,
    net_payable_amount numeric(38,2) DEFAULT 0 NOT NULL,
    manual_adjusted_minutes integer DEFAULT 0 CONSTRAINT payroll_run_items_manual_adjusted_hours_not_null NOT NULL,
    total_payroll_minutes integer DEFAULT 0 CONSTRAINT payroll_run_items_total_payroll_hours_not_null NOT NULL,
    total_deductions_amount numeric(38,2) DEFAULT 0 NOT NULL,
    meal_allowance_count integer DEFAULT 0 NOT NULL,
    meal_allowance_unit_amount_system numeric(38,2) DEFAULT 0 NOT NULL,
    meal_allowance_unit_amount numeric(38,2) DEFAULT 0 NOT NULL,
    meal_allowance_unit_amount_overridden boolean DEFAULT false CONSTRAINT payroll_run_items_meal_allowance_unit_amount_overridde_not_null NOT NULL,
    total_meal_allowance_amount numeric(38,2) DEFAULT 0 NOT NULL,
    transport_allowance_days integer DEFAULT 0 NOT NULL,
    total_transport_allowance_amount_system numeric(38,2) DEFAULT 0 CONSTRAINT payroll_run_items_transport_allowance_amount_system_not_null NOT NULL,
    total_transport_allowance_amount numeric(38,2) DEFAULT 0 CONSTRAINT payroll_run_items_transport_allowance_amount_not_null NOT NULL,
    total_transport_allowance_amount_overridden boolean DEFAULT false CONSTRAINT payroll_run_items_transport_allowance_overridden_not_null NOT NULL,
    current_month_telephone numeric(38,2),
    transport_allowance_unit_amount numeric(38,2) DEFAULT 0 NOT NULL,
    needs_recalculation boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_payroll_runs_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('CALCULATED'::character varying)::text, ('APPROVED'::character varying)::text, ('LOCKED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT chk_pri_currency_code CHECK ((length((currency_code)::text) = 3)),
    CONSTRAINT chk_pri_days_non_negative CHECK (((total_work_days >= 0) AND (total_paid_days >= 0) AND (total_absence_days >= 0))),
    CONSTRAINT chk_pri_non_negative_totals CHECK (((total_shift_minutes >= 0) AND (total_work_minutes >= 0) AND (total_absence_minutes >= 0) AND (total_paid_absence_minutes >= 0) AND (total_unpaid_absence_minutes >= 0) AND (total_compensated_minutes >= 0) AND (total_approved_minutes >= 0) AND (total_quantity >= 0) AND (total_scrap >= 0) AND (total_effective_minutes >= (0)::numeric))),
    CONSTRAINT chk_pri_note_not_empty CHECK (((note IS NULL) OR (length(TRIM(BOTH FROM note)) > 0)))
);


--
-- Name: payroll_run_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_run_items ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payroll_run_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_runs (
    id bigint NOT NULL,
    report_year integer NOT NULL,
    report_month integer NOT NULL,
    run_code character varying(255) NOT NULL,
    status character varying(255) DEFAULT 'DRAFT'::character varying NOT NULL,
    note character varying(255),
    created_by bigint,
    approved_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    approved_at timestamp with time zone,
    locked_at timestamp with time zone,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_payroll_runs_month CHECK (((report_month >= 1) AND (report_month <= 12))),
    CONSTRAINT chk_payroll_runs_note_not_empty CHECK (((note IS NULL) OR (length(TRIM(BOTH FROM note)) > 0))),
    CONSTRAINT chk_payroll_runs_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('CALCULATED'::character varying)::text, ('APPROVED'::character varying)::text, ('LOCKED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: payroll_runs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_runs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payroll_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: plastic_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plastic_categories (
    id bigint NOT NULL,
    category_no text NOT NULL,
    category_name text NOT NULL,
    norm_multiplier numeric(5,2) DEFAULT 1.0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    valid_from date DEFAULT CURRENT_DATE,
    valid_until date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_plastic_categories_category_name_not_empty CHECK ((length(TRIM(BOTH FROM category_name)) > 0)),
    CONSTRAINT chk_plastic_categories_category_no_not_empty CHECK ((length(TRIM(BOTH FROM category_no)) > 0)),
    CONSTRAINT chk_plastic_categories_no_reactivate CHECK ((NOT ((archived_at IS NOT NULL) AND (is_active = true)))),
    CONSTRAINT chk_plastic_categories_norm_multiplier_range CHECK ((norm_multiplier >= (0)::numeric)),
    CONSTRAINT chk_plastic_categories_validity CHECK (((valid_until IS NULL) OR (valid_until >= valid_from)))
);


--
-- Name: plastic_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.plastic_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.plastic_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: product_manufacturing_time_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_manufacturing_time_operations (
    id bigint NOT NULL,
    product_manufacturing_time_id bigint CONSTRAINT product_manufacturing_time__product_manufacturing_time_not_null NOT NULL,
    operation_id bigint NOT NULL,
    operation_name character varying(255) NOT NULL,
    units_per_product_snapshot integer,
    units_per_product_overridden boolean DEFAULT false CONSTRAINT product_manufacturing_time__units_per_product_overridd_not_null NOT NULL,
    units_per_product_value integer,
    norm_snapshot numeric(38,2),
    norm_overridden boolean DEFAULT false NOT NULL,
    norm_value numeric(38,2),
    norm_date_snapshot date,
    norm_date_overridden boolean DEFAULT false CONSTRAINT product_manufacturing_time_operat_norm_date_overridden_not_null NOT NULL,
    norm_date_value date,
    excluded boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_at timestamp with time zone,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_pmto_norm_value CHECK (((norm_value IS NULL) OR (norm_value >= (0)::numeric))),
    CONSTRAINT chk_pmto_units_per_product_value CHECK (((units_per_product_value IS NULL) OR (units_per_product_value > 0)))
);


--
-- Name: product_manufacturing_time_operations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.product_manufacturing_time_operations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: product_manufacturing_time_operations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.product_manufacturing_time_operations_id_seq OWNED BY public.product_manufacturing_time_operations.id;


--
-- Name: product_manufacturing_times; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_manufacturing_times (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    manufacturing_coefficient numeric(10,6),
    products_per_hour numeric(10,4),
    manufacturing_time_seconds integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_at timestamp with time zone,
    is_active boolean DEFAULT true NOT NULL,
    product_id bigint NOT NULL,
    product_name character varying(255) NOT NULL,
    date_of_issue date NOT NULL,
    title character varying(255) DEFAULT ''::text NOT NULL,
    source_request_id bigint,
    CONSTRAINT chk_pmt_manufacturing_coefficient CHECK (((manufacturing_coefficient IS NULL) OR (manufacturing_coefficient >= (0)::numeric))),
    CONSTRAINT chk_pmt_manufacturing_time_seconds CHECK (((manufacturing_time_seconds IS NULL) OR (manufacturing_time_seconds >= 0)))
);


--
-- Name: COLUMN product_manufacturing_times.source_request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.product_manufacturing_times.source_request_id IS 'The manufacturing_time_requests row that most recently produced the current state of this record. NULL when created directly.';


--
-- Name: product_manufacturing_times_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.product_manufacturing_times_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: product_manufacturing_times_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.product_manufacturing_times_id_seq OWNED BY public.product_manufacturing_times.id;


--
-- Name: production_order_deadlines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_order_deadlines (
    id bigint NOT NULL,
    production_order_id bigint NOT NULL,
    deadline_date_to date CONSTRAINT production_order_deadlines_deadline_date_not_null NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    deadline_order integer DEFAULT 1 CONSTRAINT production_order_deadlines_order_deadline_not_null NOT NULL,
    deadline_date_from date,
    quantity integer,
    CONSTRAINT chk_production_order_deadlines_archived_implies_inactive CHECK (((archived_at IS NULL) OR (is_active = false))),
    CONSTRAINT chk_production_order_deadlines_date_range CHECK (((deadline_date_from IS NULL) OR (deadline_date_from <= deadline_date_to)))
);


--
-- Name: production_order_deadlines_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_order_deadlines ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_order_deadlines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: production_order_line_item_notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_order_line_item_notes (
    id bigint NOT NULL,
    production_order_line_item_id bigint CONSTRAINT production_order_line_item_production_order_line_item_not_null1 NOT NULL,
    order_note integer DEFAULT 1 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    note character varying(255) DEFAULT ''::text NOT NULL,
    CONSTRAINT chk_production_order_line_item_notes_archived_implies_inactive CHECK (((archived_at IS NULL) OR (is_active = false)))
);


--
-- Name: production_order_line_item_notes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_order_line_item_notes ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_order_line_item_notes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: production_order_line_item_quantities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_order_line_item_quantities (
    id bigint NOT NULL,
    production_order_line_item_id bigint CONSTRAINT production_order_line_item__production_order_line_item_not_null NOT NULL,
    order_quantity integer DEFAULT 1 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    quantity integer DEFAULT 0 NOT NULL,
    delivery_deadline date,
    CONSTRAINT chk_production_order_line_item_quantities_archived_implies_inac CHECK (((archived_at IS NULL) OR (is_active = false)))
);


--
-- Name: production_order_line_item_quantities_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_order_line_item_quantities ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_order_line_item_quantities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: production_order_line_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_order_line_items (
    id bigint NOT NULL,
    production_order_id bigint NOT NULL,
    quantity integer NOT NULL,
    note character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    product_id bigint DEFAULT 1 NOT NULL,
    product_description character varying(255),
    line_order integer DEFAULT 1 NOT NULL,
    CONSTRAINT chk_production_order_line_items_quantity CHECK ((quantity > 0))
);


--
-- Name: production_order_line_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_order_line_items ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_order_line_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: production_order_mailing_lists; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_order_mailing_lists (
    id bigint NOT NULL,
    production_order_id bigint NOT NULL,
    mailing_list_id bigint NOT NULL,
    added_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE production_order_mailing_lists; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.production_order_mailing_lists IS 'Which mailing lists were selected for a production order. Records intent; the actual recipients live in production_order_recipients.';


--
-- Name: production_order_mailing_lists_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_order_mailing_lists ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_order_mailing_lists_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: production_order_recipients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_order_recipients (
    id bigint NOT NULL,
    production_order_id bigint NOT NULL,
    user_id bigint,
    recipient_email character varying(320) NOT NULL,
    recipient_name character varying(150),
    source_type character varying(20) NOT NULL,
    source_mailing_list_id bigint,
    added_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    removed_at timestamp with time zone,
    removed_by bigint,
    CONSTRAINT chk_po_recipients_added_by CHECK (((((source_type)::text = 'SYSTEM'::text) AND (added_by IS NULL)) OR (((source_type)::text <> 'SYSTEM'::text) AND (added_by IS NOT NULL)))),
    CONSTRAINT chk_po_recipients_email CHECK ((((recipient_email)::text = lower(TRIM(BOTH FROM recipient_email))) AND ((recipient_email)::text ~~ '%_@_%._%'::text) AND ((recipient_email)::text !~ '[[:space:]]'::text))),
    CONSTRAINT chk_po_recipients_name CHECK (((recipient_name IS NULL) OR (length(TRIM(BOTH FROM recipient_name)) > 0))),
    CONSTRAINT chk_po_recipients_removal_state CHECK ((((removed_at IS NULL) AND (removed_by IS NULL)) OR ((removed_at IS NOT NULL) AND (removed_by IS NOT NULL)))),
    CONSTRAINT chk_po_recipients_source_list_consistency CHECK (((((source_type)::text = 'MAILING_LIST'::text) AND (source_mailing_list_id IS NOT NULL)) OR (((source_type)::text <> 'MAILING_LIST'::text) AND (source_mailing_list_id IS NULL)))),
    CONSTRAINT chk_po_recipients_source_type CHECK (((source_type)::text = ANY (ARRAY[('MAILING_LIST'::character varying)::text, ('MANUAL'::character varying)::text, ('SYSTEM'::character varying)::text])))
);


--
-- Name: TABLE production_order_recipients; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.production_order_recipients IS 'Immutable-by-intent recipient snapshot for a production order. Later mailing-list edits never rewrite it. Removal sets removed_at/removed_by.';


--
-- Name: COLUMN production_order_recipients.recipient_email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.production_order_recipients.recipient_email IS 'The address actually used for this order, snapshotted at add time even when user_id is present.';


--
-- Name: production_order_recipients_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_order_recipients ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_order_recipients_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: production_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_orders (
    id bigint NOT NULL,
    user_id bigint,
    code character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    creation_date date,
    testing_required boolean DEFAULT false NOT NULL,
    note character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    order_date date,
    status character varying(20) DEFAULT 'CREATED'::character varying NOT NULL,
    is_high_priority boolean DEFAULT false,
    is_announced boolean DEFAULT false,
    delivery_deadline character varying(255),
    has_successive_deliveries boolean DEFAULT false,
    CONSTRAINT chk_production_orders_code_not_empty CHECK ((length(TRIM(BOTH FROM code)) > 0)),
    CONSTRAINT chk_production_orders_name_not_empty CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT chk_production_orders_status CHECK (((status)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('DELIVERED'::character varying)::text])))
);


--
-- Name: production_orders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.production_orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.production_orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.products (
    id bigint NOT NULL,
    product_name character varying(255) NOT NULL,
    product_code character varying(255),
    description character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_products_code_not_empty CHECK (((product_code IS NULL) OR (length(TRIM(BOTH FROM product_code)) > 0))),
    CONSTRAINT chk_products_name_not_empty CHECK ((length(TRIM(BOTH FROM product_name)) > 0))
);


--
-- Name: products_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.products ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_ip character varying(100),
    created_user_agent character varying(500),
    expires_at timestamp(6) with time zone NOT NULL,
    family_id character varying(64) NOT NULL,
    replaced_by_token_hash character varying(64),
    revoked_at timestamp(6) with time zone,
    revoked_reason character varying(200),
    token_hash character varying(64) NOT NULL,
    user_id bigint NOT NULL
);


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.refresh_tokens ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id bigint NOT NULL,
    role_name character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone
);


--
-- Name: roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.roles ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.roles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sample_order_line_item_notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sample_order_line_item_notes (
    id bigint NOT NULL,
    sample_order_line_item_id bigint NOT NULL,
    order_quantity integer DEFAULT 1 NOT NULL,
    note character varying(255) DEFAULT ''::text NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_sample_order_line_item_notes_archived_implies_inactive CHECK (((archived_at IS NULL) OR (is_active = false)))
);


--
-- Name: sample_order_line_item_notes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sample_order_line_item_notes ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sample_order_line_item_notes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sample_order_line_item_quantities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sample_order_line_item_quantities (
    id bigint NOT NULL,
    sample_order_line_item_id bigint CONSTRAINT sample_order_line_item_quant_sample_order_line_item_id_not_null NOT NULL,
    order_quantity integer DEFAULT 1 NOT NULL,
    quantity integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_sample_order_line_item_quantities_archived_implies_inactive CHECK (((archived_at IS NULL) OR (is_active = false)))
);


--
-- Name: sample_order_line_item_quantities_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sample_order_line_item_quantities ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sample_order_line_item_quantities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sample_order_line_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sample_order_line_items (
    id bigint NOT NULL,
    sample_order_id bigint NOT NULL,
    order_line integer,
    catalog_no character varying(255),
    quantity integer NOT NULL,
    note character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    product_id bigint NOT NULL,
    archived_at timestamp with time zone,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_sample_order_line_items_quantity_valid CHECK ((quantity > 0))
);


--
-- Name: sample_order_line_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sample_order_line_items ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sample_order_line_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sample_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sample_orders (
    id bigint NOT NULL,
    user_id bigint,
    name character varying(255) NOT NULL,
    creation_date date CONSTRAINT sample_orders_order_date_not_null NOT NULL,
    deadline_date date CONSTRAINT sample_orders_deadline_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    is_active boolean DEFAULT true NOT NULL,
    status character varying(255) DEFAULT 'created'::text NOT NULL,
    closed_by bigint,
    CONSTRAINT chk_sample_orders_name_not_empty CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT chk_sample_orders_valid_deadline CHECK ((creation_date <= deadline_date))
);


--
-- Name: sample_orders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sample_orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sample_orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: scraps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scraps (
    id bigint NOT NULL,
    period date NOT NULL,
    operation_id bigint NOT NULL,
    production_order_id bigint,
    quantity integer NOT NULL,
    note text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_scraps_period_month CHECK ((date_trunc('month'::text, (period)::timestamp with time zone) = period)),
    CONSTRAINT chk_scraps_quantity CHECK ((quantity > 0))
);


--
-- Name: scraps_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.scraps ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.scraps_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: shifts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shifts (
    id bigint NOT NULL,
    shift_code character varying(255) NOT NULL,
    name character varying(255),
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_shifts_shift_code_not_empty CHECK ((length(TRIM(BOTH FROM shift_code)) > 0)),
    CONSTRAINT chk_shifts_time_valid CHECK ((start_time <> end_time))
);


--
-- Name: shifts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.shifts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.shifts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notifications (
    id bigint NOT NULL,
    notification_event_id bigint NOT NULL,
    user_id bigint NOT NULL,
    read_at timestamp with time zone,
    dismissed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE user_notifications; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_notifications IS 'A user''s copy of a notification event. read_at IS NULL means unread; dismissed_at hides it from the active list without deleting it.';


--
-- Name: user_notifications_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_notifications ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_notifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_preferences (
    user_id bigint NOT NULL,
    theme character varying(20) DEFAULT 'SYSTEM'::character varying NOT NULL,
    language character varying(10) DEFAULT 'sr'::character varying NOT NULL,
    timezone character varying(64) DEFAULT 'Europe/Belgrade'::character varying NOT NULL,
    date_format character varying(32) DEFAULT 'dd.MM.yyyy'::character varying NOT NULL,
    time_format character varying(32) DEFAULT 'HH:mm'::character varying NOT NULL,
    number_format character varying(32) DEFAULT 'sr-RS'::character varying NOT NULL,
    ui_density character varying(20) DEFAULT 'COMFORTABLE'::character varying NOT NULL,
    rows_per_page integer DEFAULT 25 NOT NULL,
    sidebar_collapsed boolean DEFAULT false NOT NULL,
    email_notifications_enabled boolean DEFAULT true NOT NULL,
    in_app_notifications_enabled boolean DEFAULT true NOT NULL,
    ui_settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_user_preferences_rows_per_page CHECK (((rows_per_page >= 5) AND (rows_per_page <= 500))),
    CONSTRAINT chk_user_preferences_theme CHECK (((theme)::text = ANY (ARRAY[('SYSTEM'::character varying)::text, ('LIGHT'::character varying)::text, ('DARK'::character varying)::text]))),
    CONSTRAINT chk_user_preferences_ui_density CHECK (((ui_density)::text = ANY (ARRAY[('COMPACT'::character varying)::text, ('COMFORTABLE'::character varying)::text, ('SPACIOUS'::character varying)::text]))),
    CONSTRAINT chk_user_preferences_ui_settings CHECK (((jsonb_typeof(ui_settings) = 'object'::text) AND (pg_column_size(ui_settings) <= 16384)))
);


--
-- Name: TABLE user_preferences; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_preferences IS 'One row per user. Created lazily on first read (see UserPreferencesService.getOrCreateForUser); ON DELETE CASCADE because preferences have no meaning without the user.';


--
-- Name: user_registration_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_registration_requests (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    review_note character varying(1000),
    reviewed_by bigint,
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_user_registration_requests_review_note CHECK (((review_note IS NULL) OR (length(TRIM(BOTH FROM review_note)) > 0))),
    CONSTRAINT chk_user_registration_requests_review_state CHECK (((((status)::text = 'PENDING'::text) AND (reviewed_at IS NULL) AND (reviewed_by IS NULL)) OR (((status)::text = ANY (ARRAY[('APPROVED'::character varying)::text, ('DECLINED'::character varying)::text])) AND (reviewed_at IS NOT NULL) AND (reviewed_by IS NOT NULL)) OR (((status)::text = 'CANCELLED'::text) AND (reviewed_at IS NOT NULL)))),
    CONSTRAINT chk_user_registration_requests_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('DECLINED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: TABLE user_registration_requests; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_registration_requests IS 'Administrator approval record for a self-registered account. Append-only: rows are never deleted after review.';


--
-- Name: COLUMN user_registration_requests.reviewed_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_registration_requests.reviewed_by IS 'The administrator who approved or declined. Business state, not audit metadata.';


--
-- Name: user_registration_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_registration_requests ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_registration_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_saved_views; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_saved_views (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    view_key character varying(80) NOT NULL,
    name character varying(150) NOT NULL,
    filters jsonb DEFAULT '{}'::jsonb NOT NULL,
    sorting jsonb DEFAULT '[]'::jsonb NOT NULL,
    columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_user_saved_views_columns CHECK (((jsonb_typeof(columns) = 'array'::text) AND (pg_column_size(columns) <= 8192))),
    CONSTRAINT chk_user_saved_views_default_not_archived CHECK ((NOT (is_default AND (archived_at IS NOT NULL)))),
    CONSTRAINT chk_user_saved_views_filters CHECK (((jsonb_typeof(filters) = 'object'::text) AND (pg_column_size(filters) <= 32768))),
    CONSTRAINT chk_user_saved_views_name CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT chk_user_saved_views_sorting CHECK (((jsonb_typeof(sorting) = 'array'::text) AND (pg_column_size(sorting) <= 8192))),
    CONSTRAINT chk_user_saved_views_view_key CHECK ((length(TRIM(BOTH FROM view_key)) > 0))
);


--
-- Name: TABLE user_saved_views; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_saved_views IS 'Named, reusable filter/sort/column configurations owned by one user. Saved views never widen what a user is allowed to see.';


--
-- Name: user_saved_views_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_saved_views ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_saved_views_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_sessions (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    family_id character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revoked_by bigint,
    logout_at timestamp with time zone,
    device_name character varying(150),
    user_agent character varying(500),
    ip_address character varying(100),
    CONSTRAINT chk_user_sessions_revocation CHECK ((((revoked_at IS NULL) AND (revoked_by IS NULL)) OR ((revoked_at IS NOT NULL) AND (revoked_by IS NOT NULL))))
);


--
-- Name: TABLE user_sessions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_sessions IS 'One row per login (keyed by refresh_tokens.family_id). Presence is derived from last_seen_at, never from a stored boolean.';


--
-- Name: COLUMN user_sessions.last_seen_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.last_seen_at IS 'Updated by the authenticated heartbeat endpoint. The user/session are taken from the security context, never from the request body.';


--
-- Name: COLUMN user_sessions.ip_address; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.ip_address IS 'Retained for the life of the session row. Cleanup of expired sessions removes it; see the retention section of the business-rules document.';


--
-- Name: user_sessions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_sessions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_sessions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_table_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_table_preferences (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    table_key character varying(80) NOT NULL,
    settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT chk_user_table_preferences_settings CHECK (((jsonb_typeof(settings) = 'object'::text) AND (pg_column_size(settings) <= 32768))),
    CONSTRAINT chk_user_table_preferences_table_key CHECK ((length(TRIM(BOTH FROM table_key)) > 0))
);


--
-- Name: TABLE user_table_preferences; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_table_preferences IS 'Per-user column/sort/width layout for one dense table. Purely presentational: it never affects authorization or backend filtering.';


--
-- Name: user_table_preferences_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_table_preferences ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_table_preferences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(255) NOT NULL,
    password_hash character varying(255),
    email_address character varying(255) NOT NULL,
    role_id bigint NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    first_name character varying(255) NOT NULL,
    last_name character varying(255) NOT NULL,
    mobile_phone character varying(255),
    full_name character varying(255) GENERATED ALWAYS AS ((((first_name)::text || ' '::text) || (last_name)::text)) STORED NOT NULL,
    activated_at timestamp(6) with time zone,
    account_status character varying(20) DEFAULT 'PENDING_APPROVAL'::character varying NOT NULL,
    CONSTRAINT chk_users_account_status CHECK (((account_status)::text = ANY (ARRAY[('PENDING_APPROVAL'::character varying)::text, ('ACTIVE'::character varying)::text, ('DECLINED'::character varying)::text, ('SUSPENDED'::character varying)::text, ('ARCHIVED'::character varying)::text]))),
    CONSTRAINT chk_users_email_address CHECK ((length(TRIM(BOTH FROM email_address)) > 0)),
    CONSTRAINT chk_users_username_not_empty CHECK ((length(TRIM(BOTH FROM username)) > 0))
);


--
-- Name: COLUMN users.activated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.activated_at IS 'First time the account reached ACTIVE. Never cleared once set.';


--
-- Name: COLUMN users.account_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.users.account_status IS 'Authoritative account workflow state. is_active is derived from it by trg_00_users_account_status_sync.';


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: work_calendar_days; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_calendar_days (
    id bigint NOT NULL,
    calendar_date date NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    day_type character varying(30) NOT NULL,
    label character varying(255),
    updated_at timestamp(6) with time zone,
    working_override boolean,
    CONSTRAINT work_calendar_days_day_type_check CHECK (((day_type)::text = ANY (ARRAY[('WORKDAY'::character varying)::text, ('NON_WORKING'::character varying)::text, ('HOLIDAY'::character varying)::text, ('COLLECTIVE_LEAVE'::character varying)::text])))
);


--
-- Name: work_calendar_days_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.work_calendar_days ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.work_calendar_days_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: work_code_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_code_categories (
    id bigint NOT NULL,
    category_no character varying(255) NOT NULL,
    category_name character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    norm_multiplier double precision DEFAULT 1.0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    valid_from date DEFAULT CURRENT_DATE,
    valid_until date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    is_paid boolean DEFAULT true NOT NULL,
    affects_norm boolean DEFAULT true NOT NULL,
    affects_bonus boolean DEFAULT true NOT NULL,
    note text,
    hourly_rate numeric(10,2),
    fixed_hourly_rate boolean DEFAULT false NOT NULL,
    display_order integer DEFAULT 0 NOT NULL,
    affects_meal_allowance boolean DEFAULT true NOT NULL,
    base_category boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_work_code_categories_category_name_not_empty CHECK ((length(TRIM(BOTH FROM category_name)) > 0)),
    CONSTRAINT chk_work_code_categories_category_no_not_empty CHECK ((length(TRIM(BOTH FROM category_no)) > 0)),
    CONSTRAINT chk_work_code_categories_no_reactivate CHECK ((NOT ((archived_at IS NOT NULL) AND (is_active = true)))),
    CONSTRAINT chk_work_code_categories_norm_multiplier_range CHECK ((norm_multiplier >= ((0)::numeric)::double precision)),
    CONSTRAINT chk_work_code_categories_validity CHECK (((valid_until IS NULL) OR (valid_until >= valid_from)))
);


--
-- Name: work_code_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.work_code_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.work_code_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: work_code_category_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_code_category_mappings (
    id bigint NOT NULL,
    source_category_id bigint NOT NULL,
    target_category_id bigint NOT NULL,
    mapping_type character varying(100) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    valid_from date DEFAULT CURRENT_DATE NOT NULL,
    valid_until date,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    CONSTRAINT chk_work_code_category_mappings_no_reactivate CHECK ((NOT ((archived_at IS NOT NULL) AND (is_active = true)))),
    CONSTRAINT chk_work_code_category_mappings_not_same CHECK ((source_category_id <> target_category_id)),
    CONSTRAINT chk_work_code_category_mappings_type_not_empty CHECK ((length(TRIM(BOTH FROM mapping_type)) > 0)),
    CONSTRAINT chk_work_code_category_mappings_validity CHECK (((valid_until IS NULL) OR (valid_until >= valid_from)))
);


--
-- Name: work_code_category_mappings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.work_code_category_mappings ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.work_code_category_mappings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: work_log_facts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_log_facts (
    work_log_id bigint NOT NULL,
    work_shift_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    operation_id bigint NOT NULL,
    product_id bigint NOT NULL,
    production_order_id bigint,
    shift_type_id bigint NOT NULL,
    work_date date NOT NULL,
    month_start date NOT NULL,
    shift_code text NOT NULL,
    operation_start_time time without time zone NOT NULL,
    product_name text NOT NULL,
    operation_name text NOT NULL,
    production_order_code text,
    note text,
    duration_min integer NOT NULL,
    quantity integer DEFAULT 0 NOT NULL,
    scrap integer DEFAULT 0 NOT NULL,
    approved_performance_rate numeric(38,2),
    synced_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: work_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_logs (
    id bigint NOT NULL,
    work_shift_id bigint NOT NULL,
    operation_id bigint NOT NULL,
    production_order_id bigint,
    work_code_category_id bigint NOT NULL,
    start_at timestamp with time zone NOT NULL,
    end_at timestamp with time zone NOT NULL,
    duration_min integer GENERATED ALWAYS AS (floor((date_part('epoch'::text, (end_at - start_at)) / (60)::double precision))) STORED,
    quantity integer DEFAULT 0,
    scrap integer DEFAULT 0,
    note character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    archived_at timestamp with time zone,
    norm_multiplier_snapshot numeric(38,2),
    performance_rate numeric(38,2),
    approved_performance_rate numeric(38,2),
    paid_minutes numeric(38,2),
    hourly_output numeric(38,2) GENERATED ALWAYS AS (
CASE
    WHEN (quantity IS NULL) THEN NULL::numeric
    WHEN (EXTRACT(epoch FROM (end_at - start_at)) <= (0)::numeric) THEN NULL::numeric
    ELSE round((((quantity)::numeric * (3600)::numeric) / EXTRACT(epoch FROM (end_at - start_at))), 2)
END) STORED,
    effective_work_code_category_id bigint,
    CONSTRAINT chk_work_logs_business_logic CHECK ((((operation_id IS NOT NULL) AND (quantity >= 0) AND (scrap >= 0)) OR ((operation_id IS NULL) AND (quantity = 0) AND (scrap = 0)))),
    CONSTRAINT chk_work_logs_comment_not_empty CHECK (((note IS NULL) OR (length(TRIM(BOTH FROM note)) > 0))),
    CONSTRAINT chk_work_logs_duration_min CHECK (((end_at - start_at) >= '00:01:00'::interval))
);


--
-- Name: work_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.work_logs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.work_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: work_shifts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_shifts (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    shift_id bigint NOT NULL,
    supervisor_id bigint,
    start_at timestamp with time zone NOT NULL,
    end_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    last_activity_at timestamp with time zone DEFAULT now() NOT NULL,
    work_date date NOT NULL,
    day_of_week smallint GENERATED ALWAYS AS (EXTRACT(isodow FROM work_date)) STORED,
    total_minutes integer GENERATED ALWAYS AS (
CASE
    WHEN ((start_at IS NOT NULL) AND (end_at IS NOT NULL)) THEN floor((date_part('epoch'::text, (end_at - start_at)) / (60)::double precision))
    ELSE NULL::double precision
END) STORED,
    is_active boolean DEFAULT true,
    version bigint DEFAULT 0 NOT NULL,
    note character varying(255),
    employee_record_id bigint,
    work_code_category_id bigint,
    effective_work_code_category_id bigint,
    CONSTRAINT chk_work_shifts_time_valid CHECK (((start_at IS NULL) OR (end_at IS NULL) OR (end_at > start_at)))
);


--
-- Name: work_shifts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.work_shifts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.work_shifts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: product_manufacturing_time_operations id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_time_operations ALTER COLUMN id SET DEFAULT nextval('public.product_manufacturing_time_operations_id_seq'::regclass);


--
-- Name: product_manufacturing_times id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_times ALTER COLUMN id SET DEFAULT nextval('public.product_manufacturing_times_id_seq'::regclass);


--
-- Name: absence_compensations absence_compensations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_compensations
    ADD CONSTRAINT absence_compensations_pkey PRIMARY KEY (id);


--
-- Name: absence_records absence_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_records
    ADD CONSTRAINT absence_records_pkey PRIMARY KEY (id);


--
-- Name: app_settings app_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_settings
    ADD CONSTRAINT app_settings_pkey PRIMARY KEY (id);


--
-- Name: audit_actions audit_actions_action_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_actions
    ADD CONSTRAINT audit_actions_action_name_key UNIQUE (action_name);


--
-- Name: audit_actions audit_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_actions
    ADD CONSTRAINT audit_actions_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: audit_tables audit_tables_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_tables
    ADD CONSTRAINT audit_tables_pkey PRIMARY KEY (id);


--
-- Name: audit_tables audit_tables_table_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_tables
    ADD CONSTRAINT audit_tables_table_name_key UNIQUE (table_name);


--
-- Name: bonus_categories bonus_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bonus_categories
    ADD CONSTRAINT bonus_categories_pkey PRIMARY KEY (id);


--
-- Name: bonus_eligibility_rules bonus_eligibility_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bonus_eligibility_rules
    ADD CONSTRAINT bonus_eligibility_rules_pkey PRIMARY KEY (id);


--
-- Name: bonus_min_hours_rules bonus_min_hours_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bonus_min_hours_rules
    ADD CONSTRAINT bonus_min_hours_rules_pkey PRIMARY KEY (id);


--
-- Name: daily_report_categories daily_report_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_categories
    ADD CONSTRAINT daily_report_categories_pkey PRIMARY KEY (id);


--
-- Name: daily_report_recalc_queue daily_report_recalc_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_recalc_queue
    ADD CONSTRAINT daily_report_recalc_queue_pkey PRIMARY KEY (id);


--
-- Name: daily_reports daily_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_reports
    ADD CONSTRAINT daily_reports_pkey PRIMARY KEY (id);


--
-- Name: departments departments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.departments
    ADD CONSTRAINT departments_pkey PRIMARY KEY (id);


--
-- Name: employee_payroll_run_item_updates employee_payroll_run_item_updates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_payroll_run_item_updates
    ADD CONSTRAINT employee_payroll_run_item_updates_pkey PRIMARY KEY (id);


--
-- Name: employee_record_updates employee_record_updates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_record_updates
    ADD CONSTRAINT employee_record_updates_pkey PRIMARY KEY (id);


--
-- Name: employee_records employee_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_records
    ADD CONSTRAINT employee_records_pkey PRIMARY KEY (id);


--
-- Name: employees employees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_pkey PRIMARY KEY (id);


--
-- Name: app_settings ex_app_settings_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_settings
    ADD CONSTRAINT ex_app_settings_no_overlap EXCLUDE USING gist (lower((setting_key)::text) WITH =, tstzrange(valid_from, COALESCE(valid_until, 'infinity'::timestamp with time zone)) WITH &&);


--
-- Name: bonus_categories ex_bonus_categories_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bonus_categories
    ADD CONSTRAINT ex_bonus_categories_no_overlap EXCLUDE USING gist (lower((category_no)::text) WITH =, daterange(valid_from, COALESCE(valid_until, 'infinity'::date)) WITH &&);


--
-- Name: employees_bonus_history ex_employees_bonus_history_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees_bonus_history
    ADD CONSTRAINT ex_employees_bonus_history_no_overlap EXCLUDE USING gist (employee_id WITH =, daterange(start_date, COALESCE(end_date, 'infinity'::date)) WITH &&);


--
-- Name: livac_categories ex_livac_categories_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.livac_categories
    ADD CONSTRAINT ex_livac_categories_no_overlap EXCLUDE USING gist (lower(category_no) WITH =, daterange(valid_from, COALESCE(valid_until, 'infinity'::date)) WITH &&);


--
-- Name: plastic_categories ex_plastic_categories_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plastic_categories
    ADD CONSTRAINT ex_plastic_categories_no_overlap EXCLUDE USING gist (lower(category_no) WITH =, daterange(valid_from, COALESCE(valid_until, 'infinity'::date)) WITH &&);


--
-- Name: work_code_categories ex_work_code_categories_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_code_categories
    ADD CONSTRAINT ex_work_code_categories_no_overlap EXCLUDE USING gist (lower((category_no)::text) WITH =, daterange(valid_from, COALESCE(valid_until, 'infinity'::date)) WITH &&);


--
-- Name: work_shifts ex_work_shifts_no_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT ex_work_shifts_no_overlap EXCLUDE USING gist (employee_id WITH =, tstzrange(start_at, end_at) WITH &&);


--
-- Name: livac_categories livac_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.livac_categories
    ADD CONSTRAINT livac_categories_pkey PRIMARY KEY (id);


--
-- Name: mailing_list_members mailing_list_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_members
    ADD CONSTRAINT mailing_list_members_pkey PRIMARY KEY (id);


--
-- Name: mailing_lists mailing_lists_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_lists
    ADD CONSTRAINT mailing_lists_pkey PRIMARY KEY (id);


--
-- Name: manufacturing_product_times manufacturing_product_times_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_product_times
    ADD CONSTRAINT manufacturing_product_times_pkey PRIMARY KEY (id);


--
-- Name: manufacturing_time_operations manufacturing_time_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_operations
    ADD CONSTRAINT manufacturing_time_operations_pkey PRIMARY KEY (id);


--
-- Name: manufacturing_time_requests manufacturing_time_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_requests
    ADD CONSTRAINT manufacturing_time_requests_pkey PRIMARY KEY (id);


--
-- Name: monthly_report_categories monthly_report_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_categories
    ADD CONSTRAINT monthly_report_categories_pkey PRIMARY KEY (id);


--
-- Name: monthly_report_recalc_queue monthly_report_recalc_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_recalc_queue
    ADD CONSTRAINT monthly_report_recalc_queue_pkey PRIMARY KEY (id);


--
-- Name: monthly_reports monthly_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_reports
    ADD CONSTRAINT monthly_reports_pkey PRIMARY KEY (id);


--
-- Name: notification_deliveries notification_deliveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT notification_deliveries_pkey PRIMARY KEY (id);


--
-- Name: notification_events notification_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_events
    ADD CONSTRAINT notification_events_pkey PRIMARY KEY (id);


--
-- Name: operations_history operations_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operations_history
    ADD CONSTRAINT operations_history_pkey PRIMARY KEY (id);


--
-- Name: operations operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operations
    ADD CONSTRAINT operations_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: payroll_adjustment_categories payroll_adjustment_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustment_categories
    ADD CONSTRAINT payroll_adjustment_categories_pkey PRIMARY KEY (id);


--
-- Name: payroll_adjustments payroll_adjustments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT payroll_adjustments_pkey PRIMARY KEY (id);


--
-- Name: payroll_run_item_categories payroll_run_item_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_item_categories
    ADD CONSTRAINT payroll_run_item_categories_pkey PRIMARY KEY (id);


--
-- Name: payroll_run_items payroll_run_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_items
    ADD CONSTRAINT payroll_run_items_pkey PRIMARY KEY (id);


--
-- Name: payroll_runs payroll_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT payroll_runs_pkey PRIMARY KEY (id);


--
-- Name: employees_bonus_history pk_employees_bonus_history; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees_bonus_history
    ADD CONSTRAINT pk_employees_bonus_history PRIMARY KEY (id);


--
-- Name: mailing_list_access pk_mailing_list_access; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_access
    ADD CONSTRAINT pk_mailing_list_access PRIMARY KEY (mailing_list_id, user_id);


--
-- Name: plastic_categories plastic_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plastic_categories
    ADD CONSTRAINT plastic_categories_pkey PRIMARY KEY (id);


--
-- Name: product_manufacturing_time_operations product_manufacturing_time_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_time_operations
    ADD CONSTRAINT product_manufacturing_time_operations_pkey PRIMARY KEY (id);


--
-- Name: product_manufacturing_times product_manufacturing_times_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_times
    ADD CONSTRAINT product_manufacturing_times_pkey PRIMARY KEY (id);


--
-- Name: production_order_deadlines production_order_deadlines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_deadlines
    ADD CONSTRAINT production_order_deadlines_pkey PRIMARY KEY (id);


--
-- Name: production_order_line_item_notes production_order_line_item_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_item_notes
    ADD CONSTRAINT production_order_line_item_notes_pkey PRIMARY KEY (id);


--
-- Name: production_order_line_item_quantities production_order_line_item_quantities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_item_quantities
    ADD CONSTRAINT production_order_line_item_quantities_pkey PRIMARY KEY (id);


--
-- Name: production_order_line_items production_order_line_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_items
    ADD CONSTRAINT production_order_line_items_pkey PRIMARY KEY (id);


--
-- Name: production_order_mailing_lists production_order_mailing_lists_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_mailing_lists
    ADD CONSTRAINT production_order_mailing_lists_pkey PRIMARY KEY (id);


--
-- Name: production_order_recipients production_order_recipients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_recipients
    ADD CONSTRAINT production_order_recipients_pkey PRIMARY KEY (id);


--
-- Name: production_orders production_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_orders
    ADD CONSTRAINT production_orders_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: sample_order_line_item_notes sample_order_line_item_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_item_notes
    ADD CONSTRAINT sample_order_line_item_notes_pkey PRIMARY KEY (id);


--
-- Name: sample_order_line_item_quantities sample_order_line_item_quantities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_item_quantities
    ADD CONSTRAINT sample_order_line_item_quantities_pkey PRIMARY KEY (id);


--
-- Name: sample_order_line_items sample_order_line_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_items
    ADD CONSTRAINT sample_order_line_items_pkey PRIMARY KEY (id);


--
-- Name: sample_orders sample_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_orders
    ADD CONSTRAINT sample_orders_pkey PRIMARY KEY (id);


--
-- Name: scraps scraps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scraps
    ADD CONSTRAINT scraps_pkey PRIMARY KEY (id);


--
-- Name: shifts shifts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shifts
    ADD CONSTRAINT shifts_pkey PRIMARY KEY (id);


--
-- Name: payroll_adjustments ukal8innxq2iwiero5sfbtorlxf; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT ukal8innxq2iwiero5sfbtorlxf UNIQUE (payroll_run_item_id, payroll_adjustment_category_id);


--
-- Name: refresh_tokens uko2mlirhldriil2y7krapq4frt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT uko2mlirhldriil2y7krapq4frt UNIQUE (token_hash);


--
-- Name: absence_compensations uq_absence_comp_shift; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_compensations
    ADD CONSTRAINT uq_absence_comp_shift UNIQUE (absence_record_id, work_shift_id);


--
-- Name: daily_reports uq_daily; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_reports
    ADD CONSTRAINT uq_daily UNIQUE (work_shift_id);


--
-- Name: daily_report_categories uq_daily_report_category_report_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_categories
    ADD CONSTRAINT uq_daily_report_category_report_category UNIQUE (daily_report_id, work_code_category_id);


--
-- Name: daily_report_recalc_queue uq_daily_report_shift; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_recalc_queue
    ADD CONSTRAINT uq_daily_report_shift UNIQUE (work_shift_id);


--
-- Name: daily_reports uq_daily_reports_employee_shift; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_reports
    ADD CONSTRAINT uq_daily_reports_employee_shift UNIQUE (employee_id, work_shift_id);


--
-- Name: daily_report_categories uq_drc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_categories
    ADD CONSTRAINT uq_drc UNIQUE (daily_report_id, work_code_category_id);


--
-- Name: employee_payroll_run_item_updates uq_employee_payroll_run_item_updates_item_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_payroll_run_item_updates
    ADD CONSTRAINT uq_employee_payroll_run_item_updates_item_user UNIQUE (payroll_run_item_id, user_id);


--
-- Name: employee_record_updates uq_employee_record_updates_record_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_record_updates
    ADD CONSTRAINT uq_employee_record_updates_record_user UNIQUE (employee_record_id, user_id);


--
-- Name: employee_records uq_employee_records_employee_start_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_records
    ADD CONSTRAINT uq_employee_records_employee_start_date UNIQUE (employee_id, start_date);


--
-- Name: monthly_report_categories uq_monthly_report_category_report_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_categories
    ADD CONSTRAINT uq_monthly_report_category_report_category UNIQUE (monthly_report_id, work_code_category_id);


--
-- Name: monthly_reports uq_monthly_reports_employee_record_period; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_reports
    ADD CONSTRAINT uq_monthly_reports_employee_record_period UNIQUE (employee_record_id, start_date, end_date);


--
-- Name: monthly_report_categories uq_mrc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_categories
    ADD CONSTRAINT uq_mrc UNIQUE (monthly_report_id, work_code_category_id);


--
-- Name: payroll_adjustment_categories uq_payroll_adjustment_categories_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustment_categories
    ADD CONSTRAINT uq_payroll_adjustment_categories_code UNIQUE (code);


--
-- Name: payroll_adjustments uq_payroll_adjustments_item_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT uq_payroll_adjustments_item_category UNIQUE (payroll_run_item_id, payroll_adjustment_category_id);


--
-- Name: payroll_run_items uq_payroll_run_items_employee; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_items
    ADD CONSTRAINT uq_payroll_run_items_employee UNIQUE (payroll_run_id, employee_id);


--
-- Name: payroll_runs uq_payroll_runs_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT uq_payroll_runs_code UNIQUE (run_code);


--
-- Name: payroll_runs uq_payroll_runs_period; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT uq_payroll_runs_period UNIQUE (report_year, report_month);


--
-- Name: payroll_run_item_categories uq_pric_item_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_item_categories
    ADD CONSTRAINT uq_pric_item_category UNIQUE (payroll_run_item_id, work_code_category_id);


--
-- Name: production_order_mailing_lists uq_production_order_mailing_lists; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_mailing_lists
    ADD CONSTRAINT uq_production_order_mailing_lists UNIQUE (production_order_id, mailing_list_id);


--
-- Name: sample_order_line_items uq_sample_order_line_items_order_line; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_items
    ADD CONSTRAINT uq_sample_order_line_items_order_line UNIQUE (sample_order_id, order_line);


--
-- Name: work_calendar_days uq_work_calendar_days_calendar_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_calendar_days
    ADD CONSTRAINT uq_work_calendar_days_calendar_date UNIQUE (calendar_date);


--
-- Name: work_shifts uq_work_shifts_employee_shift_work_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT uq_work_shifts_employee_shift_work_date UNIQUE (employee_id, shift_id, work_date);


--
-- Name: user_notifications user_notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notifications
    ADD CONSTRAINT user_notifications_pkey PRIMARY KEY (id);


--
-- Name: user_preferences user_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferences
    ADD CONSTRAINT user_preferences_pkey PRIMARY KEY (user_id);


--
-- Name: user_registration_requests user_registration_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_registration_requests
    ADD CONSTRAINT user_registration_requests_pkey PRIMARY KEY (id);


--
-- Name: user_saved_views user_saved_views_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_saved_views
    ADD CONSTRAINT user_saved_views_pkey PRIMARY KEY (id);


--
-- Name: user_sessions user_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT user_sessions_pkey PRIMARY KEY (id);


--
-- Name: user_table_preferences user_table_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_table_preferences
    ADD CONSTRAINT user_table_preferences_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: work_calendar_days work_calendar_days_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_calendar_days
    ADD CONSTRAINT work_calendar_days_pkey PRIMARY KEY (id);


--
-- Name: work_code_categories work_code_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_code_categories
    ADD CONSTRAINT work_code_categories_pkey PRIMARY KEY (id);


--
-- Name: work_code_category_mappings work_code_category_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_code_category_mappings
    ADD CONSTRAINT work_code_category_mappings_pkey PRIMARY KEY (id);


--
-- Name: work_log_facts work_log_facts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_pkey PRIMARY KEY (work_log_id);


--
-- Name: work_logs work_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_logs
    ADD CONSTRAINT work_logs_pkey PRIMARY KEY (id);


--
-- Name: work_shifts work_shifts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT work_shifts_pkey PRIMARY KEY (id);


--
-- Name: idx_absence_compensations_absence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_compensations_absence ON public.absence_compensations USING btree (absence_record_id) WHERE (archived_at IS NULL);


--
-- Name: idx_absence_employee_shift; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_employee_shift ON public.absence_records USING btree (employee_id, work_shift_id);


--
-- Name: idx_absence_records_absence_category_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_records_absence_category_id ON public.absence_records USING btree (work_code_category_id);


--
-- Name: idx_absence_records_employee_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_records_employee_created ON public.absence_records USING btree (employee_id, created_at);


--
-- Name: idx_absence_records_employee_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_records_employee_id ON public.absence_records USING btree (employee_id);


--
-- Name: idx_absence_records_shift_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_records_shift_active ON public.absence_records USING btree (work_shift_id) WHERE (is_active = true);


--
-- Name: idx_absence_records_time_range; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_records_time_range ON public.absence_records USING btree (start_at, end_at) WHERE (is_active = true);


--
-- Name: idx_absence_records_work_shift_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_absence_records_work_shift_id ON public.absence_records USING btree (work_shift_id);


--
-- Name: idx_app_settings_valid_window; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_settings_valid_window ON public.app_settings USING btree (valid_from, valid_until);


--
-- Name: idx_audit_logs_change_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_change_time ON public.audit_logs USING btree (change_time);


--
-- Name: idx_audit_logs_record_latest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_record_latest ON public.audit_logs USING btree (table_id, record_id, change_time DESC);


--
-- Name: idx_audit_logs_table_record; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_table_record ON public.audit_logs USING btree (table_id, record_id);


--
-- Name: idx_audit_logs_table_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_table_time ON public.audit_logs USING btree (table_id, change_time DESC);


--
-- Name: idx_audit_logs_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_user_id ON public.audit_logs USING btree (user_id);


--
-- Name: idx_audit_logs_user_table_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_user_table_time ON public.audit_logs USING btree (user_id, table_id, change_time DESC);


--
-- Name: idx_bonus_eligibility_rules_period_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bonus_eligibility_rules_period_active ON public.bonus_eligibility_rules USING btree (period) WHERE (archived_at IS NULL);


--
-- Name: idx_daily_pending_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_daily_pending_lookup ON public.daily_report_recalc_queue USING btree (status, requested_at);


--
-- Name: idx_daily_queue_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_daily_queue_pending ON public.daily_report_recalc_queue USING btree (status, requested_at);


--
-- Name: idx_daily_recalc_claimed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_daily_recalc_claimed_at ON public.daily_report_recalc_queue USING btree (claimed_at);


--
-- Name: idx_daily_recalc_status_requested; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_daily_recalc_status_requested ON public.daily_report_recalc_queue USING btree (status, requested_at);


--
-- Name: idx_daily_reports_employee_work_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_daily_reports_employee_work_date ON public.daily_reports USING btree (employee_id, work_date);


--
-- Name: idx_employees_bonus_history_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_bonus_history_category ON public.employees_bonus_history USING btree (bonus_category_id);


--
-- Name: idx_employees_bonus_history_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_bonus_history_employee ON public.employees_bonus_history USING btree (employee_id);


--
-- Name: idx_employees_department_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_department_id ON public.employees USING btree (department_id);


--
-- Name: idx_mailing_list_access_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mailing_list_access_user_id ON public.mailing_list_access USING btree (user_id);


--
-- Name: idx_mailing_list_members_list_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mailing_list_members_list_active ON public.mailing_list_members USING btree (mailing_list_id) WHERE (archived_at IS NULL);


--
-- Name: idx_mailing_list_members_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mailing_list_members_user_id ON public.mailing_list_members USING btree (user_id) WHERE (user_id IS NOT NULL);


--
-- Name: idx_mailing_lists_owner_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mailing_lists_owner_active ON public.mailing_lists USING btree (owner_user_id) WHERE (archived_at IS NULL);


--
-- Name: idx_mailing_lists_visibility_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mailing_lists_visibility_active ON public.mailing_lists USING btree (visibility) WHERE (archived_at IS NULL);


--
-- Name: idx_manufacturing_product_times_product_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_product_times_product_id ON public.manufacturing_product_times USING btree (product_id);


--
-- Name: idx_manufacturing_time_operations_operation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_operations_operation_id ON public.manufacturing_time_operations USING btree (operation_id);


--
-- Name: idx_manufacturing_time_operations_product_time_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_operations_product_time_id ON public.manufacturing_time_operations USING btree (product_time_id);


--
-- Name: idx_manufacturing_time_requests_assigned_to; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_requests_assigned_to ON public.manufacturing_time_requests USING btree (assigned_to) WHERE (assigned_to IS NOT NULL);


--
-- Name: idx_manufacturing_time_requests_created_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_requests_created_by ON public.manufacturing_time_requests USING btree (created_by, created_at DESC);


--
-- Name: idx_manufacturing_time_requests_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_requests_open ON public.manufacturing_time_requests USING btree (created_at) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('IN_REVIEW'::character varying)::text]));


--
-- Name: idx_manufacturing_time_requests_product_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_requests_product_id ON public.manufacturing_time_requests USING btree (product_id);


--
-- Name: idx_manufacturing_time_requests_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_requests_status_created ON public.manufacturing_time_requests USING btree (status, created_at DESC);


--
-- Name: idx_manufacturing_time_requests_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturing_time_requests_target ON public.manufacturing_time_requests USING btree (target_manufacturing_time_id) WHERE (target_manufacturing_time_id IS NOT NULL);


--
-- Name: idx_monthly_pending_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_monthly_pending_lookup ON public.monthly_report_recalc_queue USING btree (status, requested_at);


--
-- Name: idx_monthly_queue_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_monthly_queue_pending ON public.monthly_report_recalc_queue USING btree (status, requested_at);


--
-- Name: idx_monthly_recalc_claimed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_monthly_recalc_claimed_at ON public.monthly_report_recalc_queue USING btree (claimed_at);


--
-- Name: idx_monthly_recalc_status_requested; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_monthly_recalc_status_requested ON public.monthly_report_recalc_queue USING btree (status, requested_at);


--
-- Name: idx_monthly_reports_employee_record_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_monthly_reports_employee_record_id ON public.monthly_reports USING btree (employee_record_id);


--
-- Name: idx_notification_deliveries_claimable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_deliveries_claimable ON public.notification_deliveries USING btree (next_attempt_at, id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('FAILED'::character varying)::text]));


--
-- Name: idx_notification_deliveries_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_deliveries_status_created ON public.notification_deliveries USING btree (status, created_at);


--
-- Name: idx_notification_events_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_events_created_at ON public.notification_events USING btree (created_at DESC);


--
-- Name: idx_notification_events_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_events_entity ON public.notification_events USING btree (entity_type, entity_id, created_at DESC);


--
-- Name: idx_operations_history_change_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_operations_history_change_time ON public.operations_history USING btree (change_time);


--
-- Name: idx_operations_history_changed_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_operations_history_changed_by ON public.operations_history USING btree (changed_by);


--
-- Name: idx_operations_history_operation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_operations_history_operation_id ON public.operations_history USING btree (operation_id);


--
-- Name: idx_operations_product_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_operations_product_id ON public.operations USING btree (product_id);


--
-- Name: idx_outbox_events_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_aggregate ON public.outbox_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_outbox_events_claimable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_claimable ON public.outbox_events USING btree (next_attempt_at, id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('FAILED'::character varying)::text]));


--
-- Name: idx_outbox_events_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_status_created ON public.outbox_events USING btree (status, created_at);


--
-- Name: idx_payroll_adjustments_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payroll_adjustments_item_id ON public.payroll_adjustments USING btree (payroll_run_item_id);


--
-- Name: idx_payroll_runs_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payroll_runs_period ON public.payroll_runs USING btree (report_year, report_month);


--
-- Name: idx_payroll_runs_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payroll_runs_status ON public.payroll_runs USING btree (status);


--
-- Name: idx_pmt_date_of_issue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pmt_date_of_issue ON public.product_manufacturing_times USING btree (date_of_issue DESC);


--
-- Name: idx_pmt_product_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pmt_product_id ON public.product_manufacturing_times USING btree (product_id);


--
-- Name: idx_pmt_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pmt_user_id ON public.product_manufacturing_times USING btree (user_id);


--
-- Name: idx_pmto_operation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pmto_operation_id ON public.product_manufacturing_time_operations USING btree (operation_id);


--
-- Name: idx_pmto_pmt_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pmto_pmt_id ON public.product_manufacturing_time_operations USING btree (product_manufacturing_time_id);


--
-- Name: idx_po_mailing_lists_mailing_list_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_po_mailing_lists_mailing_list_id ON public.production_order_mailing_lists USING btree (mailing_list_id);


--
-- Name: idx_po_recipients_order_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_po_recipients_order_active ON public.production_order_recipients USING btree (production_order_id) WHERE (removed_at IS NULL);


--
-- Name: idx_po_recipients_source_mailing_list; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_po_recipients_source_mailing_list ON public.production_order_recipients USING btree (production_order_id, source_mailing_list_id) WHERE ((source_mailing_list_id IS NOT NULL) AND (removed_at IS NULL));


--
-- Name: idx_pri_employee_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pri_employee_id ON public.payroll_run_items USING btree (employee_id);


--
-- Name: idx_pri_monthly_report_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pri_monthly_report_id ON public.payroll_run_items USING btree (monthly_report_id);


--
-- Name: idx_pri_payroll_run_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pri_payroll_run_id ON public.payroll_run_items USING btree (payroll_run_id);


--
-- Name: idx_pric_category_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pric_category_id ON public.payroll_run_item_categories USING btree (work_code_category_id);


--
-- Name: idx_pric_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pric_item_id ON public.payroll_run_item_categories USING btree (payroll_run_item_id);


--
-- Name: idx_production_order_deadlines_production_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_order_deadlines_production_order_id ON public.production_order_deadlines USING btree (production_order_id);


--
-- Name: idx_production_order_line_item_notes_production_order_line_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_order_line_item_notes_production_order_line_item ON public.production_order_line_item_notes USING btree (production_order_line_item_id);


--
-- Name: idx_production_order_line_item_quantities_production_order_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_order_line_item_quantities_production_order_line ON public.production_order_line_item_quantities USING btree (production_order_line_item_id);


--
-- Name: idx_production_order_line_items_product_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_order_line_items_product_id ON public.production_order_line_items USING btree (product_id);


--
-- Name: idx_production_order_line_items_production_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_order_line_items_production_order_id ON public.production_order_line_items USING btree (production_order_id);


--
-- Name: idx_production_orders_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_orders_status ON public.production_orders USING btree (status);


--
-- Name: idx_production_orders_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_orders_user_id ON public.production_orders USING btree (user_id);


--
-- Name: idx_refresh_token_family_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_token_family_id ON public.refresh_tokens USING btree (family_id);


--
-- Name: idx_refresh_token_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_token_user_id ON public.refresh_tokens USING btree (user_id);


--
-- Name: idx_sample_order_line_item_notes_sample_order_line_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_order_line_item_notes_sample_order_line_item_id ON public.sample_order_line_item_notes USING btree (sample_order_line_item_id);


--
-- Name: idx_sample_order_line_item_quantities_sample_order_line_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_order_line_item_quantities_sample_order_line_item_id ON public.sample_order_line_item_quantities USING btree (sample_order_line_item_id);


--
-- Name: idx_sample_order_line_items_product_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_order_line_items_product_id ON public.sample_order_line_items USING btree (product_id);


--
-- Name: idx_sample_order_line_items_sample_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_order_line_items_sample_order_id ON public.sample_order_line_items USING btree (sample_order_id);


--
-- Name: idx_sample_orders_closed_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_orders_closed_by ON public.sample_orders USING btree (closed_by);


--
-- Name: idx_sample_orders_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_orders_user_id ON public.sample_orders USING btree (user_id);


--
-- Name: idx_scraps_operation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scraps_operation_id ON public.scraps USING btree (operation_id);


--
-- Name: idx_scraps_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scraps_period ON public.scraps USING btree (period);


--
-- Name: idx_scraps_period_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scraps_period_operation ON public.scraps USING btree (period, operation_id);


--
-- Name: idx_scraps_production_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scraps_production_order_id ON public.scraps USING btree (production_order_id);


--
-- Name: idx_shift_activity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_shift_activity ON public.work_shifts USING btree (start_at, last_activity_at DESC);


--
-- Name: idx_user_notifications_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_notifications_unread ON public.user_notifications USING btree (user_id) WHERE ((read_at IS NULL) AND (dismissed_at IS NULL));


--
-- Name: idx_user_notifications_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_notifications_user_active ON public.user_notifications USING btree (user_id, created_at DESC) WHERE (dismissed_at IS NULL);


--
-- Name: idx_user_registration_requests_pending_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_registration_requests_pending_created ON public.user_registration_requests USING btree (created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_user_registration_requests_reviewed_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_registration_requests_reviewed_by ON public.user_registration_requests USING btree (reviewed_by) WHERE (reviewed_by IS NOT NULL);


--
-- Name: idx_user_registration_requests_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_registration_requests_status_created ON public.user_registration_requests USING btree (status, created_at DESC);


--
-- Name: idx_user_registration_requests_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_registration_requests_user_id ON public.user_registration_requests USING btree (user_id);


--
-- Name: idx_user_saved_views_user_key_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_saved_views_user_key_active ON public.user_saved_views USING btree (user_id, view_key) WHERE (archived_at IS NULL);


--
-- Name: idx_user_sessions_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_sessions_expires_at ON public.user_sessions USING btree (expires_at) WHERE (revoked_at IS NULL);


--
-- Name: idx_user_sessions_user_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_sessions_user_live ON public.user_sessions USING btree (user_id, last_seen_at DESC) WHERE (revoked_at IS NULL);


--
-- Name: idx_users_account_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_account_status ON public.users USING btree (account_status);


--
-- Name: idx_users_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role_id ON public.users USING btree (role_id);


--
-- Name: idx_wlf_date_shift_product_operation_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_date_shift_product_operation_employee ON public.work_log_facts USING btree (work_date, shift_type_id, product_id, operation_id, employee_id);


--
-- Name: idx_wlf_employee_work_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_employee_work_date ON public.work_log_facts USING btree (employee_id, work_date);


--
-- Name: idx_wlf_month_start; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_month_start ON public.work_log_facts USING btree (month_start);


--
-- Name: idx_wlf_note_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_note_trgm ON public.work_log_facts USING gin (note public.gin_trgm_ops);


--
-- Name: idx_wlf_operation_start_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_operation_start_time ON public.work_log_facts USING btree (operation_start_time);


--
-- Name: idx_wlf_operation_work_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_operation_work_date ON public.work_log_facts USING btree (operation_id, work_date);


--
-- Name: idx_wlf_product_operation_work_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_product_operation_work_date ON public.work_log_facts USING btree (product_id, operation_id, work_date);


--
-- Name: idx_wlf_production_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_production_order ON public.work_log_facts USING btree (production_order_id);


--
-- Name: idx_wlf_work_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wlf_work_date ON public.work_log_facts USING btree (work_date);


--
-- Name: idx_work_calendar_days_calendar_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_calendar_days_calendar_date ON public.work_calendar_days USING btree (calendar_date);


--
-- Name: idx_work_logs_active_shift; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_active_shift ON public.work_logs USING btree (work_shift_id) WHERE (is_active = true);


--
-- Name: idx_work_logs_operation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_operation_id ON public.work_logs USING btree (operation_id);


--
-- Name: idx_work_logs_production_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_production_order_id ON public.work_logs USING btree (production_order_id);


--
-- Name: idx_work_logs_shift_active_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_shift_active_time ON public.work_logs USING btree (work_shift_id, start_at, end_at) WHERE (is_active = true);


--
-- Name: idx_work_logs_shift_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_shift_operation ON public.work_logs USING btree (work_shift_id, operation_id);


--
-- Name: idx_work_logs_shift_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_shift_time ON public.work_logs USING btree (work_shift_id, start_at, end_at) WHERE (is_active = true);


--
-- Name: idx_work_logs_start_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_start_at ON public.work_logs USING btree (start_at);


--
-- Name: idx_work_logs_work_code_category_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_logs_work_code_category_id ON public.work_logs USING btree (work_code_category_id);


--
-- Name: idx_work_shifts_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_shifts_employee ON public.work_shifts USING btree (employee_id);


--
-- Name: idx_work_shifts_employee_workdate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_shifts_employee_workdate ON public.work_shifts USING btree (employee_id, work_date);


--
-- Name: idx_work_shifts_shift; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_shifts_shift ON public.work_shifts USING btree (shift_id);


--
-- Name: idx_work_shifts_supervisor_activity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_shifts_supervisor_activity ON public.work_shifts USING btree (supervisor_id, start_at, employee_id, last_activity_at DESC);


--
-- Name: idx_work_shifts_work_code_category_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_shifts_work_code_category_id ON public.work_shifts USING btree (work_code_category_id);


--
-- Name: idx_ws_supervisor_start; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ws_supervisor_start ON public.work_shifts USING btree (supervisor_id, start_at DESC);


--
-- Name: uq_bonus_categories_category_no_name_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_bonus_categories_category_no_name_ci ON public.bonus_categories USING btree (lower((category_no)::text), lower((category_name)::text));


--
-- Name: uq_bonus_min_hours_rules_active_period; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_bonus_min_hours_rules_active_period ON public.bonus_min_hours_rules USING btree (period) WHERE (archived_at IS NULL);


--
-- Name: uq_daily_queue_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_daily_queue_pending ON public.daily_report_recalc_queue USING btree (employee_id, work_date) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uq_departments_name_ic; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_departments_name_ic ON public.departments USING btree (lower((name)::text));


--
-- Name: uq_employees_employee_no_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_employees_employee_no_ci ON public.employees USING btree (lower((employee_no)::text)) WHERE (employee_no IS NOT NULL);


--
-- Name: uq_livac_categories_category_no_name_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_livac_categories_category_no_name_ci ON public.livac_categories USING btree (lower(category_no), lower(category_name));


--
-- Name: uq_mailing_list_members_email_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mailing_list_members_email_active ON public.mailing_list_members USING btree (mailing_list_id, lower((external_email)::text)) WHERE ((external_email IS NOT NULL) AND (archived_at IS NULL));


--
-- Name: uq_mailing_list_members_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mailing_list_members_user_active ON public.mailing_list_members USING btree (mailing_list_id, user_id) WHERE ((user_id IS NOT NULL) AND (archived_at IS NULL));


--
-- Name: uq_mailing_lists_owner_name_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mailing_lists_owner_name_active ON public.mailing_lists USING btree (owner_user_id, lower((name)::text)) WHERE (archived_at IS NULL);


--
-- Name: uq_monthly_recalc_active_period; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_monthly_recalc_active_period ON public.monthly_report_recalc_queue USING btree (employee_id, report_year, report_month) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('IN_PROGRESS'::character varying)::text]));


--
-- Name: uq_notification_deliveries_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_notification_deliveries_email ON public.notification_deliveries USING btree (notification_event_id, lower((recipient_email)::text)) WHERE ((channel)::text = 'EMAIL'::text);


--
-- Name: uq_notification_deliveries_in_app; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_notification_deliveries_in_app ON public.notification_deliveries USING btree (notification_event_id, recipient_user_id) WHERE ((channel)::text = 'IN_APP'::text);


--
-- Name: uq_notification_events_outbox_event_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_notification_events_outbox_event_id ON public.notification_events USING btree (outbox_event_id) WHERE (outbox_event_id IS NOT NULL);


--
-- Name: uq_operations_product_op_name_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_operations_product_op_name_ci ON public.operations USING btree (product_id, lower((op_name)::text));


--
-- Name: uq_plastic_categories_no_name_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_plastic_categories_no_name_ci ON public.plastic_categories USING btree (lower(category_no), lower(category_name));


--
-- Name: uq_pmt_source_request_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_pmt_source_request_id ON public.product_manufacturing_times USING btree (source_request_id) WHERE (source_request_id IS NOT NULL);


--
-- Name: uq_po_recipients_order_email_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_po_recipients_order_email_active ON public.production_order_recipients USING btree (production_order_id, lower((recipient_email)::text)) WHERE (removed_at IS NULL);


--
-- Name: uq_production_orders_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_production_orders_code ON public.production_orders USING btree (lower((code)::text));


--
-- Name: uq_products_product_code_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_products_product_code_ci ON public.products USING btree (lower((product_code)::text));


--
-- Name: uq_roles_name_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_roles_name_ci ON public.roles USING btree (lower((role_name)::text));


--
-- Name: uq_sample_order_line_items_catalog_number; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_sample_order_line_items_catalog_number ON public.sample_order_line_items USING btree (catalog_no) WHERE (catalog_no IS NOT NULL);


--
-- Name: uq_shifts_shift_code_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_shifts_shift_code_ci ON public.shifts USING btree (lower((shift_code)::text));


--
-- Name: uq_user_notifications_event_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_notifications_event_user ON public.user_notifications USING btree (notification_event_id, user_id);


--
-- Name: uq_user_registration_requests_one_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_registration_requests_one_pending ON public.user_registration_requests USING btree (user_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uq_user_saved_views_name_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_saved_views_name_active ON public.user_saved_views USING btree (user_id, view_key, lower((name)::text)) WHERE (archived_at IS NULL);


--
-- Name: uq_user_saved_views_one_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_saved_views_one_default ON public.user_saved_views USING btree (user_id, view_key) WHERE (is_default AND (archived_at IS NULL));


--
-- Name: uq_user_sessions_family_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_sessions_family_id ON public.user_sessions USING btree (family_id);


--
-- Name: uq_user_table_preferences_user_table; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_table_preferences_user_table ON public.user_table_preferences USING btree (user_id, table_key);


--
-- Name: uq_users_email_address_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_email_address_ci ON public.users USING btree (lower((email_address)::text));


--
-- Name: uq_users_username_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_username_ci ON public.users USING btree (lower((username)::text));


--
-- Name: uq_work_code_categories_no_name_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_work_code_categories_no_name_ci ON public.work_code_categories USING btree (lower((category_no)::text), lower((category_name)::text));


--
-- Name: uq_work_code_category_mappings_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_work_code_category_mappings_active ON public.work_code_category_mappings USING btree (source_category_id, target_category_id, lower((mapping_type)::text)) WHERE (is_active = true);


--
-- Name: work_logs after_delete_work_logs_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER after_delete_work_logs_track_activity AFTER DELETE ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.trg_work_logs_track_activity();


--
-- Name: work_shifts after_delete_work_shifts_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER after_delete_work_shifts_track_activity AFTER DELETE ON public.work_shifts FOR EACH ROW EXECUTE FUNCTION public.trg_work_shifts_track_activity();


--
-- Name: work_logs after_insert_work_logs_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER after_insert_work_logs_track_activity AFTER INSERT ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.trg_work_logs_track_activity();


--
-- Name: work_shifts after_insert_work_shifts_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER after_insert_work_shifts_track_activity AFTER INSERT ON public.work_shifts FOR EACH ROW EXECUTE FUNCTION public.trg_work_shifts_track_activity();


--
-- Name: work_logs after_update_work_logs_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER after_update_work_logs_track_activity AFTER UPDATE ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.trg_work_logs_track_activity();


--
-- Name: work_shifts after_update_work_shifts_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER after_update_work_shifts_track_activity AFTER UPDATE ON public.work_shifts FOR EACH ROW EXECUTE FUNCTION public.trg_work_shifts_track_activity();


--
-- Name: product_manufacturing_time_operations before_insert_update_pmto_resolve_values; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER before_insert_update_pmto_resolve_values BEFORE INSERT OR UPDATE ON public.product_manufacturing_time_operations FOR EACH ROW EXECUTE FUNCTION public.trg_pmto_resolve_values();


--
-- Name: product_manufacturing_times before_update_pmt_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER before_update_pmt_set_updated_at BEFORE UPDATE ON public.product_manufacturing_times FOR EACH ROW EXECUTE FUNCTION public.trg_pmt_touch_updated_at();


--
-- Name: product_manufacturing_time_operations before_update_pmto_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER before_update_pmto_set_updated_at BEFORE UPDATE ON public.product_manufacturing_time_operations FOR EACH ROW EXECUTE FUNCTION public.trg_pmto_touch_updated_at();


--
-- Name: users trg_00_users_account_status_sync; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_00_users_account_status_sync BEFORE INSERT OR UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.sync_user_account_status();


--
-- Name: employees_bonus_history trg_01_employees_bonus_history_close_prev; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_01_employees_bonus_history_close_prev BEFORE INSERT ON public.employees_bonus_history FOR EACH ROW EXECUTE FUNCTION public.trg_close_previous_employee_bonus();


--
-- Name: employees trg_01_employees_clear_archive_on_reactivate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_01_employees_clear_archive_on_reactivate BEFORE UPDATE ON public.employees FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();


--
-- Name: manufacturing_product_times trg_01_fill_product_time_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_01_fill_product_time_name BEFORE INSERT ON public.manufacturing_product_times FOR EACH ROW EXECUTE FUNCTION public.fill_product_time_name();


--
-- Name: production_order_line_items trg_01_production_order_line_items_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_01_production_order_line_items_archived_at BEFORE UPDATE ON public.production_order_line_items FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: products trg_01_products_clear_archive_on_reactivate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_01_products_clear_archive_on_reactivate BEFORE UPDATE ON public.products FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();


--
-- Name: users trg_01_users_clear_archive_on_reactivate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_01_users_clear_archive_on_reactivate BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.clear_archived_at_on_reactivate();


--
-- Name: absence_records trg_02_absence_records_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_absence_records_archived_at BEFORE UPDATE ON public.absence_records FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: bonus_categories trg_02_bonus_categories_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_bonus_categories_archived_at BEFORE UPDATE ON public.bonus_categories FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: departments trg_02_departments_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_departments_archived_at BEFORE UPDATE ON public.departments FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: employees trg_02_employees_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_employees_archived_at BEFORE UPDATE ON public.employees FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: livac_categories trg_02_livac_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_livac_archived_at BEFORE UPDATE ON public.livac_categories FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: manufacturing_product_times trg_02_manufacturing_product_times_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_manufacturing_product_times_archived_at BEFORE UPDATE ON public.manufacturing_product_times FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: manufacturing_time_operations trg_02_manufacturing_time_operations_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_manufacturing_time_operations_archived_at BEFORE UPDATE ON public.manufacturing_time_operations FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: operations trg_02_operations_manual_archive_flag; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_operations_manual_archive_flag BEFORE UPDATE ON public.operations FOR EACH ROW EXECUTE FUNCTION public.clear_archived_flag_on_manual_archive();


--
-- Name: plastic_categories trg_02_plastic_categories_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_plastic_categories_archived_at BEFORE UPDATE ON public.plastic_categories FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: production_orders trg_02_production_orders_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_production_orders_archived_at BEFORE UPDATE ON public.production_orders FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: scraps trg_02_scraps_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_scraps_archived_at BEFORE UPDATE ON public.scraps FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: sample_order_line_items trg_02_set_sample_line_no; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_set_sample_line_no BEFORE INSERT ON public.sample_order_line_items FOR EACH ROW EXECUTE FUNCTION public.set_sample_line_no();


--
-- Name: shifts trg_02_shifts_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_shifts_archived_at BEFORE UPDATE ON public.shifts FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: users trg_02_users_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_users_archived_at BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: work_code_categories trg_02_work_code_categories_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_work_code_categories_archived_at BEFORE UPDATE ON public.work_code_categories FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: work_code_category_mappings trg_02_work_code_category_mappings_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_work_code_category_mappings_archived_at BEFORE UPDATE ON public.work_code_category_mappings FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: work_logs trg_02_work_logs_archived_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_02_work_logs_archived_at BEFORE UPDATE ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: absence_records trg_03_absence_records_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_absence_records_updated_at BEFORE UPDATE ON public.absence_records FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: bonus_categories trg_03_bonus_categories_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_bonus_categories_updated_at BEFORE UPDATE ON public.bonus_categories FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: bonus_eligibility_rules trg_03_bonus_eligibility_rules_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_bonus_eligibility_rules_updated_at BEFORE UPDATE ON public.bonus_eligibility_rules FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: departments trg_03_departments_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_departments_updated_at BEFORE UPDATE ON public.departments FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: employees trg_03_employees_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_employees_updated_at BEFORE UPDATE ON public.employees FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: livac_categories trg_03_livac_categories_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_livac_categories_updated_at BEFORE UPDATE ON public.livac_categories FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: mailing_lists trg_03_mailing_lists_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_mailing_lists_updated_at BEFORE UPDATE ON public.mailing_lists FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: manufacturing_product_times trg_03_manufacturing_product_times_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_manufacturing_product_times_updated_at BEFORE UPDATE ON public.manufacturing_product_times FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: manufacturing_time_operations trg_03_manufacturing_time_operations_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_manufacturing_time_operations_updated_at BEFORE UPDATE ON public.manufacturing_time_operations FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: manufacturing_time_requests trg_03_manufacturing_time_requests_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_manufacturing_time_requests_updated_at BEFORE UPDATE ON public.manufacturing_time_requests FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: notification_deliveries trg_03_notification_deliveries_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_notification_deliveries_updated_at BEFORE UPDATE ON public.notification_deliveries FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: operations trg_03_operations_block_manual_reactivate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_operations_block_manual_reactivate BEFORE UPDATE ON public.operations FOR EACH ROW EXECUTE FUNCTION public.block_manual_operation_reactivate();


--
-- Name: payroll_adjustments trg_03_payroll_adjustments_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_payroll_adjustments_updated_at BEFORE UPDATE ON public.payroll_adjustments FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: payroll_run_item_categories trg_03_payroll_run_item_categories_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_payroll_run_item_categories_updated_at BEFORE UPDATE ON public.payroll_run_item_categories FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: payroll_run_items trg_03_payroll_run_items_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_payroll_run_items_updated_at BEFORE UPDATE ON public.payroll_run_items FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: payroll_runs trg_03_payroll_runs_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_payroll_runs_updated_at BEFORE UPDATE ON public.payroll_runs FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: plastic_categories trg_03_plastic_categories_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_plastic_categories_updated_at BEFORE UPDATE ON public.plastic_categories FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: production_order_line_items trg_03_production_order_line_items_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_production_order_line_items_updated_at BEFORE UPDATE ON public.production_order_line_items FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: production_orders trg_03_production_orders_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_production_orders_updated_at BEFORE UPDATE ON public.production_orders FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: products trg_03_products_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_products_updated_at BEFORE UPDATE ON public.products FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: sample_order_line_items trg_03_sample_order_line_items_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_sample_order_line_items_updated_at BEFORE UPDATE ON public.sample_order_line_items FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: sample_orders trg_03_sample_orders_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_sample_orders_updated_at BEFORE UPDATE ON public.sample_orders FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: scraps trg_03_scraps_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_scraps_updated_at BEFORE UPDATE ON public.scraps FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: shifts trg_03_shifts_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_shifts_updated_at BEFORE UPDATE ON public.shifts FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: user_preferences trg_03_user_preferences_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_user_preferences_updated_at BEFORE UPDATE ON public.user_preferences FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: user_registration_requests trg_03_user_registration_requests_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_user_registration_requests_updated_at BEFORE UPDATE ON public.user_registration_requests FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: user_saved_views trg_03_user_saved_views_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_user_saved_views_updated_at BEFORE UPDATE ON public.user_saved_views FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: user_table_preferences trg_03_user_table_preferences_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_user_table_preferences_updated_at BEFORE UPDATE ON public.user_table_preferences FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: users trg_03_users_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_users_updated_at BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: work_code_categories trg_03_work_code_categories_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_work_code_categories_updated_at BEFORE UPDATE ON public.work_code_categories FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: work_code_category_mappings trg_03_work_code_category_mappings_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_work_code_category_mappings_updated_at BEFORE UPDATE ON public.work_code_category_mappings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: work_logs trg_03_work_logs_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_work_logs_updated_at BEFORE UPDATE ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: work_shifts trg_03_work_shifts_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_03_work_shifts_updated_at BEFORE UPDATE ON public.work_shifts FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: operations trg_04_operations_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_04_operations_updated_at BEFORE UPDATE ON public.operations FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: products trg_04_products_archive_cascade; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_04_products_archive_cascade AFTER UPDATE ON public.products FOR EACH ROW WHEN ((old.is_active AND (NOT new.is_active))) EXECUTE FUNCTION public.archive_product_and_operations();


--
-- Name: production_orders trg_05_archive_order_items; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_05_archive_order_items AFTER UPDATE ON public.production_orders FOR EACH ROW EXECUTE FUNCTION public.archive_production_order_items();


--
-- Name: operations trg_05_operations_history; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_05_operations_history AFTER UPDATE ON public.operations FOR EACH ROW EXECUTE FUNCTION public.operations_history_trigger_fn();


--
-- Name: products trg_05_products_revive_operations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_05_products_revive_operations AFTER UPDATE ON public.products FOR EACH ROW WHEN (((NOT old.is_active) AND new.is_active)) EXECUTE FUNCTION public.revive_operations_when_product_reactivated();


--
-- Name: absence_records trg_audit_logs_absence_records; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_absence_records AFTER INSERT OR DELETE OR UPDATE ON public.absence_records FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: bonus_categories trg_audit_logs_bonus_categories; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_bonus_categories AFTER INSERT OR DELETE OR UPDATE ON public.bonus_categories FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: bonus_eligibility_rules trg_audit_logs_bonus_eligibility_rules; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_bonus_eligibility_rules AFTER INSERT OR DELETE OR UPDATE ON public.bonus_eligibility_rules FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: departments trg_audit_logs_departments; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_departments AFTER INSERT OR DELETE OR UPDATE ON public.departments FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: employees trg_audit_logs_employees; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_employees AFTER INSERT OR DELETE OR UPDATE ON public.employees FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: employees_bonus_history trg_audit_logs_employees_bonus_history; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_employees_bonus_history AFTER INSERT OR DELETE OR UPDATE ON public.employees_bonus_history FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: livac_categories trg_audit_logs_livac_categories; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_livac_categories AFTER INSERT OR DELETE OR UPDATE ON public.livac_categories FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: mailing_list_members trg_audit_logs_mailing_list_members; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_mailing_list_members AFTER INSERT OR DELETE OR UPDATE ON public.mailing_list_members FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: mailing_lists trg_audit_logs_mailing_lists; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_mailing_lists AFTER INSERT OR DELETE OR UPDATE ON public.mailing_lists FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: manufacturing_product_times trg_audit_logs_manufacturing_product_times; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_manufacturing_product_times AFTER INSERT OR DELETE OR UPDATE ON public.manufacturing_product_times FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: manufacturing_time_operations trg_audit_logs_manufacturing_time_operations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_manufacturing_time_operations AFTER INSERT OR DELETE OR UPDATE ON public.manufacturing_time_operations FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: manufacturing_time_requests trg_audit_logs_manufacturing_time_requests; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_manufacturing_time_requests AFTER INSERT OR DELETE OR UPDATE ON public.manufacturing_time_requests FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: notification_events trg_audit_logs_notification_events; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_notification_events AFTER INSERT OR DELETE OR UPDATE ON public.notification_events FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: operations trg_audit_logs_operations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_operations AFTER INSERT OR DELETE OR UPDATE ON public.operations FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: operations_history trg_audit_logs_operations_history; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_operations_history AFTER INSERT OR DELETE OR UPDATE ON public.operations_history FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: plastic_categories trg_audit_logs_plastic_categories; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_plastic_categories AFTER INSERT OR DELETE OR UPDATE ON public.plastic_categories FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: product_manufacturing_times trg_audit_logs_product_manufacturing_times; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_product_manufacturing_times AFTER INSERT OR DELETE OR UPDATE ON public.product_manufacturing_times FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: production_order_line_items trg_audit_logs_production_order_line_items; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_production_order_line_items AFTER INSERT OR DELETE OR UPDATE ON public.production_order_line_items FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: production_order_mailing_lists trg_audit_logs_production_order_mailing_lists; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_production_order_mailing_lists AFTER INSERT OR DELETE OR UPDATE ON public.production_order_mailing_lists FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: production_order_recipients trg_audit_logs_production_order_recipients; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_production_order_recipients AFTER INSERT OR DELETE OR UPDATE ON public.production_order_recipients FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: production_orders trg_audit_logs_production_orders; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_production_orders AFTER INSERT OR DELETE OR UPDATE ON public.production_orders FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: products trg_audit_logs_products; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_products AFTER INSERT OR DELETE OR UPDATE ON public.products FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: roles trg_audit_logs_roles; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_roles AFTER INSERT OR DELETE OR UPDATE ON public.roles FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: sample_order_line_items trg_audit_logs_sample_order_line_items; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_sample_order_line_items AFTER INSERT OR DELETE OR UPDATE ON public.sample_order_line_items FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: sample_orders trg_audit_logs_sample_orders; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_sample_orders AFTER INSERT OR DELETE OR UPDATE ON public.sample_orders FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: scraps trg_audit_logs_scraps; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_scraps AFTER INSERT OR DELETE OR UPDATE ON public.scraps FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: shifts trg_audit_logs_shifts; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_shifts AFTER INSERT OR DELETE OR UPDATE ON public.shifts FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: user_registration_requests trg_audit_logs_user_registration_requests; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_user_registration_requests AFTER INSERT OR DELETE OR UPDATE ON public.user_registration_requests FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: users trg_audit_logs_users; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_users AFTER INSERT OR DELETE OR UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: work_code_categories trg_audit_logs_work_code_categories; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_work_code_categories AFTER INSERT OR DELETE OR UPDATE ON public.work_code_categories FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: work_code_category_mappings trg_audit_logs_work_code_category_mappings; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_work_code_category_mappings AFTER INSERT OR DELETE OR UPDATE ON public.work_code_category_mappings FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: work_logs trg_audit_logs_work_logs; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_work_logs AFTER INSERT OR DELETE OR UPDATE ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: work_shifts trg_audit_logs_work_shifts; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_work_shifts AFTER INSERT OR DELETE OR UPDATE ON public.work_shifts FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


--
-- Name: payroll_run_items trg_payroll_run_items_track_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_payroll_run_items_track_activity AFTER INSERT OR DELETE OR UPDATE ON public.payroll_run_items FOR EACH ROW EXECUTE FUNCTION public.trg_payroll_run_item_track_activity();


--
-- Name: roles trg_roles_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON public.roles FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: operations trg_set_archived_at_operations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_set_archived_at_operations BEFORE UPDATE OF is_active ON public.operations FOR EACH ROW WHEN ((old.is_active IS DISTINCT FROM new.is_active)) EXECUTE FUNCTION public.set_archived_at_on_deactivate();


--
-- Name: work_shifts trg_shift_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_shift_activity BEFORE UPDATE ON public.work_shifts FOR EACH ROW EXECUTE FUNCTION public.trg_shift_update_activity();


--
-- Name: work_calendar_days trg_work_calendar_days_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_work_calendar_days_updated_at BEFORE UPDATE ON public.work_calendar_days FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: work_logs trg_work_logs_activity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_work_logs_activity AFTER INSERT OR UPDATE ON public.work_logs FOR EACH ROW EXECUTE FUNCTION public.update_shift_activity();


--
-- Name: refresh_tokens fk1lih5y2npsf8u5o3vhdb9y0os; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT fk1lih5y2npsf8u5o3vhdb9y0os FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: audit_logs fk4opitllu7r35dht5u19xlaqy0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT fk4opitllu7r35dht5u19xlaqy0 FOREIGN KEY (action_id) REFERENCES public.audit_actions(id);


--
-- Name: employee_record_updates fk5cs4m55lydy4fda3k9c7p7tin; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_record_updates
    ADD CONSTRAINT fk5cs4m55lydy4fda3k9c7p7tin FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: work_shifts fk6e08hsp8bd4tkql97ql088r4c; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT fk6e08hsp8bd4tkql97ql088r4c FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: employee_record_updates fk858rrfayc3g9udk0j7mtrq7hd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_record_updates
    ADD CONSTRAINT fk858rrfayc3g9udk0j7mtrq7hd FOREIGN KEY (employee_record_id) REFERENCES public.employee_records(id);


--
-- Name: payroll_run_items fk__monthly_report; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_items
    ADD CONSTRAINT fk__monthly_report FOREIGN KEY (monthly_report_id) REFERENCES public.monthly_reports(id) ON DELETE RESTRICT;


--
-- Name: absence_records fk_absence_records_employee_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_records
    ADD CONSTRAINT fk_absence_records_employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(id) ON DELETE RESTRICT;


--
-- Name: absence_records fk_absence_records_work_code_category_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_records
    ADD CONSTRAINT fk_absence_records_work_code_category_id FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id) ON DELETE RESTRICT;


--
-- Name: absence_records fk_absence_records_work_shift_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_records
    ADD CONSTRAINT fk_absence_records_work_shift_id FOREIGN KEY (work_shift_id) REFERENCES public.work_shifts(id) ON DELETE RESTRICT;


--
-- Name: audit_logs fk_audit_logs_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT fk_audit_logs_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: absence_compensations fk_comp_absence; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_compensations
    ADD CONSTRAINT fk_comp_absence FOREIGN KEY (absence_record_id) REFERENCES public.absence_records(id) ON DELETE CASCADE;


--
-- Name: absence_compensations fk_comp_shift; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.absence_compensations
    ADD CONSTRAINT fk_comp_shift FOREIGN KEY (work_shift_id) REFERENCES public.work_shifts(id) ON DELETE RESTRICT;


--
-- Name: daily_report_recalc_queue fk_daily_report_recalc_queue_employees; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_recalc_queue
    ADD CONSTRAINT fk_daily_report_recalc_queue_employees FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: daily_report_recalc_queue fk_daily_report_recalc_queue_work_shifts; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_recalc_queue
    ADD CONSTRAINT fk_daily_report_recalc_queue_work_shifts FOREIGN KEY (work_shift_id) REFERENCES public.work_shifts(id);


--
-- Name: daily_reports fk_daily_reports_employee_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_reports
    ADD CONSTRAINT fk_daily_reports_employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: daily_reports fk_daily_reports_work_shifts; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_reports
    ADD CONSTRAINT fk_daily_reports_work_shifts FOREIGN KEY (work_shift_id) REFERENCES public.work_shifts(id);


--
-- Name: daily_report_categories fk_drc_category; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_categories
    ADD CONSTRAINT fk_drc_category FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: daily_report_categories fk_drc_daily; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_report_categories
    ADD CONSTRAINT fk_drc_daily FOREIGN KEY (daily_report_id) REFERENCES public.daily_reports(id) ON DELETE CASCADE;


--
-- Name: employee_payroll_run_item_updates fk_employee_payroll_run_item_updates_payroll_run_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_payroll_run_item_updates
    ADD CONSTRAINT fk_employee_payroll_run_item_updates_payroll_run_item FOREIGN KEY (payroll_run_item_id) REFERENCES public.payroll_run_items(id);


--
-- Name: employee_payroll_run_item_updates fk_employee_payroll_run_item_updates_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_payroll_run_item_updates
    ADD CONSTRAINT fk_employee_payroll_run_item_updates_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: employees_bonus_history fk_employees_bonus_history_bonus_category_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees_bonus_history
    ADD CONSTRAINT fk_employees_bonus_history_bonus_category_id FOREIGN KEY (bonus_category_id) REFERENCES public.bonus_categories(id) ON DELETE RESTRICT;


--
-- Name: employees_bonus_history fk_employees_bonus_history_changed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees_bonus_history
    ADD CONSTRAINT fk_employees_bonus_history_changed_by FOREIGN KEY (changed_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: employees_bonus_history fk_employees_bonus_history_employee_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees_bonus_history
    ADD CONSTRAINT fk_employees_bonus_history_employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(id) ON DELETE RESTRICT;


--
-- Name: employees fk_employees_default_work_category_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT fk_employees_default_work_category_id FOREIGN KEY (default_work_category_id) REFERENCES public.work_code_categories(id) ON DELETE SET NULL;


--
-- Name: employees fk_employees_department_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT fk_employees_department_id FOREIGN KEY (department_id) REFERENCES public.departments(id) ON DELETE RESTRICT;


--
-- Name: mailing_list_access fk_mailing_list_access_granted_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_access
    ADD CONSTRAINT fk_mailing_list_access_granted_by FOREIGN KEY (granted_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: mailing_list_access fk_mailing_list_access_mailing_list_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_access
    ADD CONSTRAINT fk_mailing_list_access_mailing_list_id FOREIGN KEY (mailing_list_id) REFERENCES public.mailing_lists(id) ON DELETE CASCADE;


--
-- Name: mailing_list_access fk_mailing_list_access_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_access
    ADD CONSTRAINT fk_mailing_list_access_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: mailing_list_members fk_mailing_list_members_created_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_members
    ADD CONSTRAINT fk_mailing_list_members_created_by FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: mailing_list_members fk_mailing_list_members_mailing_list_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_members
    ADD CONSTRAINT fk_mailing_list_members_mailing_list_id FOREIGN KEY (mailing_list_id) REFERENCES public.mailing_lists(id) ON DELETE CASCADE;


--
-- Name: mailing_list_members fk_mailing_list_members_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_list_members
    ADD CONSTRAINT fk_mailing_list_members_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: mailing_lists fk_mailing_lists_owner_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mailing_lists
    ADD CONSTRAINT fk_mailing_lists_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_product_times fk_manufacturing_product_times_product_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_product_times
    ADD CONSTRAINT fk_manufacturing_product_times_product_id FOREIGN KEY (product_id) REFERENCES public.products(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_operations fk_manufacturing_time_operations_operation_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_operations
    ADD CONSTRAINT fk_manufacturing_time_operations_operation_id FOREIGN KEY (operation_id) REFERENCES public.operations(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_operations fk_manufacturing_time_operations_product_time_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_operations
    ADD CONSTRAINT fk_manufacturing_time_operations_product_time_id FOREIGN KEY (product_time_id) REFERENCES public.manufacturing_product_times(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_requests fk_manufacturing_time_requests_assigned_to; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_assigned_to FOREIGN KEY (assigned_to) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_requests fk_manufacturing_time_requests_created_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_created_by FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_requests fk_manufacturing_time_requests_processed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_processed_by FOREIGN KEY (processed_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_requests fk_manufacturing_time_requests_product_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_product_id FOREIGN KEY (product_id) REFERENCES public.products(id) ON DELETE RESTRICT;


--
-- Name: manufacturing_time_requests fk_manufacturing_time_requests_target; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_target FOREIGN KEY (target_manufacturing_time_id) REFERENCES public.product_manufacturing_times(id) ON DELETE RESTRICT;


--
-- Name: monthly_report_categories fk_monthly_report_categories_monthly_reports; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_categories
    ADD CONSTRAINT fk_monthly_report_categories_monthly_reports FOREIGN KEY (monthly_report_id) REFERENCES public.monthly_reports(id) ON DELETE CASCADE;


--
-- Name: monthly_report_categories fk_monthly_report_categories_work_code_categories; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_categories
    ADD CONSTRAINT fk_monthly_report_categories_work_code_categories FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: monthly_report_recalc_queue fk_monthly_report_recalc_queue_employees; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_report_recalc_queue
    ADD CONSTRAINT fk_monthly_report_recalc_queue_employees FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: notification_deliveries fk_notification_deliveries_notification_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT fk_notification_deliveries_notification_event_id FOREIGN KEY (notification_event_id) REFERENCES public.notification_events(id) ON DELETE RESTRICT;


--
-- Name: notification_deliveries fk_notification_deliveries_recipient_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_deliveries
    ADD CONSTRAINT fk_notification_deliveries_recipient_user_id FOREIGN KEY (recipient_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: notification_events fk_notification_events_actor_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_events
    ADD CONSTRAINT fk_notification_events_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: notification_events fk_notification_events_outbox_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_events
    ADD CONSTRAINT fk_notification_events_outbox_event_id FOREIGN KEY (outbox_event_id) REFERENCES public.outbox_events(id) ON DELETE RESTRICT;


--
-- Name: operations_history fk_operations_history_changed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operations_history
    ADD CONSTRAINT fk_operations_history_changed_by FOREIGN KEY (changed_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: operations_history fk_operations_history_operation_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operations_history
    ADD CONSTRAINT fk_operations_history_operation_id FOREIGN KEY (operation_id) REFERENCES public.operations(id) ON DELETE RESTRICT;


--
-- Name: operations fk_operations_product_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operations
    ADD CONSTRAINT fk_operations_product_id FOREIGN KEY (product_id) REFERENCES public.products(id) ON DELETE RESTRICT;


--
-- Name: operations fk_operations_work_code_category; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operations
    ADD CONSTRAINT fk_operations_work_code_category FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id) ON DELETE SET NULL;


--
-- Name: payroll_adjustments fk_payroll_adjustments_created_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT fk_payroll_adjustments_created_by FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: payroll_adjustments fk_payroll_adjustments_edited_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT fk_payroll_adjustments_edited_by FOREIGN KEY (edited_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: payroll_adjustments fk_payroll_adjustments_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT fk_payroll_adjustments_item FOREIGN KEY (payroll_run_item_id) REFERENCES public.payroll_run_items(id) ON DELETE CASCADE;


--
-- Name: payroll_adjustments fk_payroll_adjustments_pac; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_adjustments
    ADD CONSTRAINT fk_payroll_adjustments_pac FOREIGN KEY (payroll_adjustment_category_id) REFERENCES public.payroll_adjustment_categories(id) ON DELETE CASCADE;


--
-- Name: payroll_run_items fk_payroll_run_items_employee; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_items
    ADD CONSTRAINT fk_payroll_run_items_employee FOREIGN KEY (employee_id) REFERENCES public.employees(id) ON DELETE RESTRICT;


--
-- Name: payroll_run_items fk_payroll_run_items_payroll_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_items
    ADD CONSTRAINT fk_payroll_run_items_payroll_run FOREIGN KEY (payroll_run_id) REFERENCES public.payroll_runs(id) ON DELETE CASCADE;


--
-- Name: payroll_runs fk_payroll_runs_approved_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT fk_payroll_runs_approved_by FOREIGN KEY (approved_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: payroll_runs fk_payroll_runs_created_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT fk_payroll_runs_created_by FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: product_manufacturing_times fk_pmt_source_request_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_times
    ADD CONSTRAINT fk_pmt_source_request_id FOREIGN KEY (source_request_id) REFERENCES public.manufacturing_time_requests(id) ON DELETE RESTRICT;


--
-- Name: production_order_mailing_lists fk_po_mailing_lists_added_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_mailing_lists
    ADD CONSTRAINT fk_po_mailing_lists_added_by FOREIGN KEY (added_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: production_order_mailing_lists fk_po_mailing_lists_mailing_list_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_mailing_lists
    ADD CONSTRAINT fk_po_mailing_lists_mailing_list_id FOREIGN KEY (mailing_list_id) REFERENCES public.mailing_lists(id) ON DELETE RESTRICT;


--
-- Name: production_order_mailing_lists fk_po_mailing_lists_production_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_mailing_lists
    ADD CONSTRAINT fk_po_mailing_lists_production_order_id FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id) ON DELETE CASCADE;


--
-- Name: production_order_recipients fk_po_recipients_added_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_recipients
    ADD CONSTRAINT fk_po_recipients_added_by FOREIGN KEY (added_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: production_order_recipients fk_po_recipients_production_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_recipients
    ADD CONSTRAINT fk_po_recipients_production_order_id FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id) ON DELETE CASCADE;


--
-- Name: production_order_recipients fk_po_recipients_removed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_recipients
    ADD CONSTRAINT fk_po_recipients_removed_by FOREIGN KEY (removed_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: production_order_recipients fk_po_recipients_source_mailing_list_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_recipients
    ADD CONSTRAINT fk_po_recipients_source_mailing_list_id FOREIGN KEY (source_mailing_list_id) REFERENCES public.mailing_lists(id) ON DELETE RESTRICT;


--
-- Name: production_order_recipients fk_po_recipients_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_recipients
    ADD CONSTRAINT fk_po_recipients_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: payroll_run_item_categories fk_pric_category; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_item_categories
    ADD CONSTRAINT fk_pric_category FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id) ON DELETE RESTRICT;


--
-- Name: payroll_run_item_categories fk_pric_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_run_item_categories
    ADD CONSTRAINT fk_pric_item FOREIGN KEY (payroll_run_item_id) REFERENCES public.payroll_run_items(id) ON DELETE CASCADE;


--
-- Name: production_order_deadlines fk_production_order_deadlines_production_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_deadlines
    ADD CONSTRAINT fk_production_order_deadlines_production_order_id FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id) ON DELETE CASCADE;


--
-- Name: production_order_line_item_notes fk_production_order_line_item_notes_production_order_line_item_; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_item_notes
    ADD CONSTRAINT fk_production_order_line_item_notes_production_order_line_item_ FOREIGN KEY (production_order_line_item_id) REFERENCES public.production_order_line_items(id) ON DELETE CASCADE;


--
-- Name: production_order_line_item_quantities fk_production_order_line_item_quantities_production_order_line_; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_item_quantities
    ADD CONSTRAINT fk_production_order_line_item_quantities_production_order_line_ FOREIGN KEY (production_order_line_item_id) REFERENCES public.production_order_line_items(id) ON DELETE CASCADE;


--
-- Name: production_order_line_items fk_production_order_line_items_product_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_items
    ADD CONSTRAINT fk_production_order_line_items_product_id FOREIGN KEY (product_id) REFERENCES public.products(id) ON DELETE RESTRICT;


--
-- Name: production_order_line_items fk_production_order_line_items_production_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_order_line_items
    ADD CONSTRAINT fk_production_order_line_items_production_order_id FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id) ON DELETE RESTRICT;


--
-- Name: production_orders fk_production_orders_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_orders
    ADD CONSTRAINT fk_production_orders_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: sample_order_line_item_notes fk_sample_order_line_item_notes_sample_order_line_item_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_item_notes
    ADD CONSTRAINT fk_sample_order_line_item_notes_sample_order_line_item_id FOREIGN KEY (sample_order_line_item_id) REFERENCES public.sample_order_line_items(id) ON DELETE CASCADE;


--
-- Name: sample_order_line_item_quantities fk_sample_order_line_item_quantities_sample_order_line_item_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_item_quantities
    ADD CONSTRAINT fk_sample_order_line_item_quantities_sample_order_line_item_id FOREIGN KEY (sample_order_line_item_id) REFERENCES public.sample_order_line_items(id) ON DELETE CASCADE;


--
-- Name: sample_order_line_items fk_sample_order_line_items_product_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_items
    ADD CONSTRAINT fk_sample_order_line_items_product_id FOREIGN KEY (product_id) REFERENCES public.products(id) ON DELETE RESTRICT;


--
-- Name: sample_orders fk_sample_orders_closed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_orders
    ADD CONSTRAINT fk_sample_orders_closed_by FOREIGN KEY (closed_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: sample_order_line_items fk_sample_orders_line_items_sample_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_order_line_items
    ADD CONSTRAINT fk_sample_orders_line_items_sample_order_id FOREIGN KEY (sample_order_id) REFERENCES public.sample_orders(id) ON DELETE RESTRICT;


--
-- Name: sample_orders fk_sample_orders_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sample_orders
    ADD CONSTRAINT fk_sample_orders_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: scraps fk_scraps_operation_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scraps
    ADD CONSTRAINT fk_scraps_operation_id FOREIGN KEY (operation_id) REFERENCES public.operations(id) ON DELETE RESTRICT;


--
-- Name: scraps fk_scraps_production_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scraps
    ADD CONSTRAINT fk_scraps_production_order_id FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id) ON DELETE RESTRICT;


--
-- Name: user_notifications fk_user_notifications_notification_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notifications
    ADD CONSTRAINT fk_user_notifications_notification_event_id FOREIGN KEY (notification_event_id) REFERENCES public.notification_events(id) ON DELETE RESTRICT;


--
-- Name: user_notifications fk_user_notifications_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notifications
    ADD CONSTRAINT fk_user_notifications_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_preferences fk_user_preferences_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferences
    ADD CONSTRAINT fk_user_preferences_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: user_registration_requests fk_user_registration_requests_reviewed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_registration_requests
    ADD CONSTRAINT fk_user_registration_requests_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_registration_requests fk_user_registration_requests_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_registration_requests
    ADD CONSTRAINT fk_user_registration_requests_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_saved_views fk_user_saved_views_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_saved_views
    ADD CONSTRAINT fk_user_saved_views_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: user_sessions fk_user_sessions_revoked_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT fk_user_sessions_revoked_by FOREIGN KEY (revoked_by) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_sessions fk_user_sessions_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT fk_user_sessions_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_table_preferences fk_user_table_preferences_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_table_preferences
    ADD CONSTRAINT fk_user_table_preferences_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: users fk_users_role_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_role_id FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE RESTRICT;


--
-- Name: work_code_category_mappings fk_work_code_category_mappings_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_code_category_mappings
    ADD CONSTRAINT fk_work_code_category_mappings_source FOREIGN KEY (source_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: work_code_category_mappings fk_work_code_category_mappings_target; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_code_category_mappings
    ADD CONSTRAINT fk_work_code_category_mappings_target FOREIGN KEY (target_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: work_logs fk_work_logs_operation_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_logs
    ADD CONSTRAINT fk_work_logs_operation_id FOREIGN KEY (operation_id) REFERENCES public.operations(id) ON DELETE RESTRICT;


--
-- Name: work_logs fk_work_logs_production_order_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_logs
    ADD CONSTRAINT fk_work_logs_production_order_id FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id) ON DELETE RESTRICT;


--
-- Name: work_logs fk_work_logs_work_code_category_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_logs
    ADD CONSTRAINT fk_work_logs_work_code_category_id FOREIGN KEY (work_code_category_id) REFERENCES public.work_code_categories(id) ON DELETE RESTRICT;


--
-- Name: work_logs fk_work_logs_work_shift_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_logs
    ADD CONSTRAINT fk_work_logs_work_shift_id FOREIGN KEY (work_shift_id) REFERENCES public.work_shifts(id) ON DELETE RESTRICT;


--
-- Name: work_shifts fk_work_shifts_employee_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT fk_work_shifts_employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(id) ON DELETE RESTRICT;


--
-- Name: work_shifts fk_work_shifts_shift_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT fk_work_shifts_shift_id FOREIGN KEY (shift_id) REFERENCES public.shifts(id) ON DELETE RESTRICT;


--
-- Name: work_shifts fk_work_shifts_supervisor_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT fk_work_shifts_supervisor_id FOREIGN KEY (supervisor_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: employee_records fkc2ukuk1u6wh3d2aede7snpbc1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_records
    ADD CONSTRAINT fkc2ukuk1u6wh3d2aede7snpbc1 FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: work_shifts fkdc1erurhioyh5t5n91hgt7ml7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT fkdc1erurhioyh5t5n91hgt7ml7 FOREIGN KEY (employee_record_id) REFERENCES public.employee_records(id);


--
-- Name: work_logs fkntnh6ot00udg1p94rhrfxr873; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_logs
    ADD CONSTRAINT fkntnh6ot00udg1p94rhrfxr873 FOREIGN KEY (effective_work_code_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: audit_logs fknwomukgowaadnw1hfun0f6xyy; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT fknwomukgowaadnw1hfun0f6xyy FOREIGN KEY (table_id) REFERENCES public.audit_tables(id);


--
-- Name: work_shifts fkqh1jq6dbge7sw68lgd7iu28t4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_shifts
    ADD CONSTRAINT fkqh1jq6dbge7sw68lgd7iu28t4 FOREIGN KEY (effective_work_code_category_id) REFERENCES public.work_code_categories(id);


--
-- Name: monthly_reports monthly_reports_employee_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_reports
    ADD CONSTRAINT monthly_reports_employee_record_id_fkey FOREIGN KEY (employee_record_id) REFERENCES public.employee_records(id);


--
-- Name: product_manufacturing_time_operations product_manufacturing_time_op_product_manufacturing_time_i_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_time_operations
    ADD CONSTRAINT product_manufacturing_time_op_product_manufacturing_time_i_fkey FOREIGN KEY (product_manufacturing_time_id) REFERENCES public.product_manufacturing_times(id) ON DELETE CASCADE;


--
-- Name: product_manufacturing_time_operations product_manufacturing_time_operations_operation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_time_operations
    ADD CONSTRAINT product_manufacturing_time_operations_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES public.operations(id);


--
-- Name: product_manufacturing_times product_manufacturing_times_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_times
    ADD CONSTRAINT product_manufacturing_times_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id);


--
-- Name: product_manufacturing_times product_manufacturing_times_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_manufacturing_times
    ADD CONSTRAINT product_manufacturing_times_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: work_log_facts work_log_facts_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: work_log_facts work_log_facts_operation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES public.operations(id);


--
-- Name: work_log_facts work_log_facts_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id);


--
-- Name: work_log_facts work_log_facts_production_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_production_order_id_fkey FOREIGN KEY (production_order_id) REFERENCES public.production_orders(id);


--
-- Name: work_log_facts work_log_facts_shift_type_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_shift_type_id_fkey FOREIGN KEY (shift_type_id) REFERENCES public.shifts(id);


--
-- Name: work_log_facts work_log_facts_work_log_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_work_log_id_fkey FOREIGN KEY (work_log_id) REFERENCES public.work_logs(id) ON DELETE CASCADE;


--
-- Name: work_log_facts work_log_facts_work_shift_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_log_facts
    ADD CONSTRAINT work_log_facts_work_shift_id_fkey FOREIGN KEY (work_shift_id) REFERENCES public.work_shifts(id);


--
-- PostgreSQL database dump complete
--


