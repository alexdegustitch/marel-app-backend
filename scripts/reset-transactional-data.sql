-- ============================================================================
-- RESET TRANSAKCIONIH PODATAKA — marel_app
-- ============================================================================
-- Briše sve "radne" podatke (zaposlene, kartone, obračune, naloge, kupce,
-- odsustva, notifikacije, zahteve, vremena izrade, mailing liste, kalendar...)
-- a OSTAVLJA konfiguraciju koja objašnjava rad programa:
--
--   OSTAJE: users, roles, user_preferences/sessions/saved_views/table_preferences,
--           refresh_tokens, payroll_field_access, app_settings,
--           products, product_families, product_types, product_type_attributes,
--           product_attribute_values, product_type_operations, images, files,
--           departments, shifts, compensation_schemes,
--           work_code_categories (+ translations, mappings, mapping_types,
--           scheme_rules), bonus_categories, bonus_eligibility_rules,
--           bonus_min_hours_rules (+ history), livac/plastic kategorije,
--           payroll_adjustment_categories (+ translations, scheme_rules,
--           sections), payroll_time_adjustment_categories,
--           employee_payroll_value_definitions, performance_marks (šifarnik
--           Ocene), audit_actions, audit_tables,
--           DEFAULT vremena i pauze smena (employee_shift_*_periods sa
--           employee_id IS NULL), SAMOSTALNE sistemske operacije
--           (is_standalone = true: Pauza, odsustva — bez njih recalc ne radi).
--
--   BRIŠE SE: sve ostalo (vidi sekcije ispod).
--
-- Cela skripta je JEDNA transakcija: ako bilo koji DELETE pukne na FK,
-- ništa se ne menja. Pre pokretanja OBAVEZNO uraditi backup (vidi uputstvo).
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 0) Odveži korisničke naloge od radnika (users ostaju, employees se brišu)
-- ----------------------------------------------------------------------------
UPDATE users SET employee_id = NULL WHERE employee_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 1) Obračuni (payroll)
-- ----------------------------------------------------------------------------
DELETE FROM employee_payroll_run_item_updates;
-- payroll_run_item_handovers ima append-only triger koji zabranjuje DELETE;
-- TRUNCATE ne pali row trigere, tabelu niko ne referencira, pa je ovo jedini
-- način da se isprazni bez diranja trigera:
TRUNCATE payroll_run_item_handovers;
DELETE FROM payroll_adjustments;          -- i ručne (CUSTOM) stavke
DELETE FROM payroll_time_adjustments;
DELETE FROM payroll_run_item_categories;
DELETE FROM payroll_change_requests;      -- zahtevi za vraćanje meseca
DELETE FROM payroll_run_items;
DELETE FROM payroll_runs;

-- ----------------------------------------------------------------------------
-- 2) Mesečni izveštaji + recalc red
-- ----------------------------------------------------------------------------
DELETE FROM monthly_report_categories;
DELETE FROM monthly_report_recalc_queue;
DELETE FROM monthly_reports;

-- ----------------------------------------------------------------------------
-- 3) Odsustva, prekovremeni, godišnji/bolovanja
-- ----------------------------------------------------------------------------
DELETE FROM absence_compensations;
DELETE FROM overtime_records;
DELETE FROM absence_records;
DELETE FROM employee_leave_periods;

-- ----------------------------------------------------------------------------
-- 4) Škart (referencira naloge i operacije — mora pre njih)
-- ----------------------------------------------------------------------------
DELETE FROM monthly_scraps;

-- ----------------------------------------------------------------------------
-- 5) Kartoni / radni dnevnik
-- ----------------------------------------------------------------------------
DELETE FROM work_log_facts;
DELETE FROM work_logs;
DELETE FROM daily_report_categories;
DELETE FROM daily_report_recalc_queue;
DELETE FROM daily_reports;
DELETE FROM work_shifts;

-- ----------------------------------------------------------------------------
-- 6) Vremena izrade
--    manufacturing_time_requests i product_manufacturing_times se referenciraju
--    MEĐUSOBNO, a CHECK (chk_manufacturing_time_requests_result_state) ne da da
--    se COMPLETED zahtevu skine rezultat — zato jedan TRUNCATE preko svih pet
--    tabela odjednom (sve međusobne FK veze su unutar ovog skupa, spolja ih
--    niko ne referencira). Mora PRE brisanja naloga: zahtevi drže FK na
--    production_order_line_items.
-- ----------------------------------------------------------------------------
TRUNCATE manufacturing_time_requests,
         product_manufacturing_times,
         product_manufacturing_time_operations,
         manufacturing_product_times,
         manufacturing_time_operations;

-- ----------------------------------------------------------------------------
-- 7) Radni nalozi (proizvodni + uzorci), beleške, rokovi, mejlovi, zahtevi
--    razrade (uključujući interne self-zahteve supervizora)
-- ----------------------------------------------------------------------------
DELETE FROM order_notes;
DELETE FROM order_reminders;
DELETE FROM production_order_deadlines;
DELETE FROM production_order_scope_request_operations;
DELETE FROM production_order_scope_request_items;
DELETE FROM production_order_scope_requests;
DELETE FROM production_order_line_item_notes;
DELETE FROM production_order_line_item_quantities;
DELETE FROM production_order_line_items;
DELETE FROM production_order_recipients;
DELETE FROM production_order_mailing_lists;
DELETE FROM production_order_email_threads;
DELETE FROM production_orders;

DELETE FROM sample_order_line_item_notes;
DELETE FROM sample_order_line_item_quantities;
DELETE FROM sample_order_line_items;
DELETE FROM sample_order_recipients;
DELETE FROM sample_order_mailing_lists;
DELETE FROM sample_order_email_threads;
DELETE FROM sample_orders;

-- ----------------------------------------------------------------------------
-- 8) Kupci (tek posle naloga, nalozi ih referenciraju)
-- ----------------------------------------------------------------------------
DELETE FROM customers;

-- ----------------------------------------------------------------------------
-- 9) Operacije i norme
--    Samostalne sistemske operacije (is_standalone = true: Pauza, GO,
--    bolovanja...) OSTAJU — bez njih unos odsustava i pauza ne radi.
-- ----------------------------------------------------------------------------
DELETE FROM operation_norm_activations
 WHERE operation_id IN (SELECT id FROM operations WHERE NOT is_standalone);
DELETE FROM operation_norm_versions
 WHERE operation_id IN (SELECT id FROM operations WHERE NOT is_standalone);
DELETE FROM operations_history;
DELETE FROM operations WHERE NOT is_standalone;

-- ----------------------------------------------------------------------------
-- 10) Zaposleni i sve njihove istorije
-- ----------------------------------------------------------------------------
DELETE FROM employees_bonus_history;
DELETE FROM employee_compensation_scheme_history;
DELETE FROM employee_work_category_periods;
DELETE FROM employee_payroll_value_history;   -- satnice po radniku
DELETE FROM employee_record_updates;
DELETE FROM employee_records;
DELETE FROM employee_employment_periods;
DELETE FROM department_head_periods;
-- Lična radna vremena/pauze smena se brišu; redovi sa employee_id IS NULL su
-- verzionisani DEFAULT smene (šifarnik) i OSTAJU:
DELETE FROM employee_shift_time_periods  WHERE employee_id IS NOT NULL;
DELETE FROM employee_shift_break_periods WHERE employee_id IS NOT NULL;
DELETE FROM employees;

-- ----------------------------------------------------------------------------
-- 11) Raspored administracije + kalendar rada
-- ----------------------------------------------------------------------------
DELETE FROM admin_schedule_entries;
DELETE FROM work_calendar_days;

-- ----------------------------------------------------------------------------
-- 12) Mailing liste (posle naloga — join tabele su već obrisane)
-- ----------------------------------------------------------------------------
DELETE FROM mailing_list_access;
DELETE FROM mailing_list_members;
DELETE FROM mailing_lists;

-- ----------------------------------------------------------------------------
-- 13) Notifikacije i outbox
-- ----------------------------------------------------------------------------
DELETE FROM notification_deliveries;
DELETE FROM user_notifications;
DELETE FROM notification_events;
DELETE FROM outbox_events;

-- ----------------------------------------------------------------------------
-- 14) Zahtevi vezani za korisničke naloge
-- ----------------------------------------------------------------------------
DELETE FROM user_registration_requests;
DELETE FROM email_change_requests;

-- ----------------------------------------------------------------------------
-- 15) Istorija parametara: Vrednost radnog sata + Maksimalni procentualni
--     učinak. app_settings je verzionisan (valid_from/valid_until): zatvoreni
--     redovi su istorija i brišu se; tekući red (valid_until IS NULL) ostaje,
--     ali mu se valid_from vraća na početak lanca da pokrivenost po datumu
--     ostane neprekinuta od seed datuma. Prvo DELETE pa UPDATE, da se
--     valid_from ne sudari sa obrisanim redovima.
-- ----------------------------------------------------------------------------
CREATE TEMP TABLE _chain_start ON COMMIT DROP AS
    SELECT setting_key, min(valid_from) AS min_from
      FROM app_settings
     WHERE setting_key IN ('hourly_rate', 'max_efficiency_percent')
     GROUP BY setting_key;

DELETE FROM app_settings
 WHERE setting_key IN ('hourly_rate', 'max_efficiency_percent')
   AND valid_until IS NOT NULL;

UPDATE app_settings s
   SET valid_from = c.min_from
  FROM _chain_start c
 WHERE s.setting_key = c.setting_key
   AND s.valid_until IS NULL;

-- ----------------------------------------------------------------------------
-- 16) Mesečni bonusi — minimalni sati za bonus po mesecu (bonus_min_hours_rules)
--     i subotnja pravila po mesecu (bonus_eligibility_rules: broj subota →
--     iznos bonusa, po godinama/mesecima). Oboje se izvodi iz kalendara rada,
--     koji je obrisan; „Kreiraj bonuse" ih pravi ponovo za nov mesec.
--     Šifarnik kategorija bonusa (bonus_categories B1–B5) OSTAJE.
-- ----------------------------------------------------------------------------
DELETE FROM bonus_min_hours_rule_history;
DELETE FROM bonus_min_hours_rules;
DELETE FROM bonus_eligibility_rules;

-- ----------------------------------------------------------------------------
-- 17) Izvedeno / tehničko
-- ----------------------------------------------------------------------------
DELETE FROM dashboard_insights;
-- Audit log se briše POSLEDNJI: gornji DELETE-ovi pune audit kroz trigere,
-- pa i taj šum odlazi ovim redom. Time nestaje i kompletna audit istorija —
-- zakomentariši ako audit trail treba da preživi reset.
DELETE FROM audit_logs;

-- ----------------------------------------------------------------------------
-- (OPCIONO) Slike — u ovoj šemi slike postoje SAMO na proizvodima, tipovima
-- i porodicama proizvoda, a proizvodi ostaju. Zato je blok zakomentarisan;
-- odkomentariši samo ako hoćeš proizvode BEZ slika.
-- ----------------------------------------------------------------------------
-- DELETE FROM images;
-- DELETE FROM files;

COMMIT;

-- ----------------------------------------------------------------------------
-- (OPCIONO, posle COMMIT-a) Vraćanje ID brojača na 1 za ispražnjene tabele,
-- da novi podaci kreću od malih ID-jeva. Pokreni posebno ako želiš:
-- ----------------------------------------------------------------------------
-- DO $$
-- DECLARE t text;
-- BEGIN
--   FOREACH t IN ARRAY ARRAY[
--     'employees','employee_records','work_shifts','work_logs','daily_reports',
--     'monthly_reports','payroll_runs','payroll_run_items','production_orders',
--     'sample_orders','customers','operations','absence_records','monthly_scraps',
--     'overtime_records','mailing_lists','notification_events','audit_logs'
--   ] LOOP
--     PERFORM setval(pg_get_serial_sequence(t, 'id'), 1, false);
--   END LOOP;
-- END $$;
