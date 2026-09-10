-- =============================================================================
-- A role carries the name people read, and a few settings say themselves in Serbian
-- =============================================================================
-- WHAT CHANGES
--   roles        — adds a nullable `display_name` column and fills it for the six
--                  roles the application knows about.
--   app_settings — rewrites five `description` values that were entered in English
--                  into Serbian. Only the descriptions; no value, validity or key
--                  is touched.
--
-- WHY roles.display_name
--   `roles.role_name` holds the identifiers authorization compares against —
--   `admin`, `supervisor`, `commercial`, `developer`,
--   `production_coordinator`, `accountant`. They are not words to show a factory
--   administrator, and the word a role is shown under is a company decision, not
--   an engineering one: here `supervisor` is what the company calls its
--   "administrator", while `admin` is the owner. Keeping the shown name as data
--   lets it be corrected without a code release, and read the same by every
--   screen and, later, the codebook that edits it.
--
--   NULL is allowed and meaningful: a role added tomorrow has no shown name until
--   somebody gives it one, and the reader falls back to the identifier rather
--   than to a blank.
--
-- WHY the description rewrites
--   These five settings were seeded by hand on the running database with English
--   descriptions ("Hourly rate", ...); the parameters screen prints the
--   description under the Serbian title, so the English showed through. The rows
--   live only in the live database — the baseline dump carries an empty
--   app_settings section — so the correction is a data update here, matched on
--   the exact English text so it hits only those rows and is a no-op once done.
--
-- MIGRATION IMPACT
--   · Additive and safe. The new column is nullable with no default, so adding
--     it rewrites no existing row and locks only briefly.
--   · The roles UPDATE matches on lower(role_name); unknown roles are left NULL.
--   · The app_settings UPDATEs match on the exact current description and value
--     type, so they touch only the intended rows and re-running does nothing.
--   · Values, validity windows and keys are untouched — payroll history and its
--     auditability are unaffected.
-- =============================================================================

-- --- roles.display_name -----------------------------------------------------

ALTER TABLE public.roles
    ADD COLUMN IF NOT EXISTS display_name character varying(255);

UPDATE public.roles SET display_name = 'Direktor'                WHERE lower(role_name) = 'admin'                  AND display_name IS NULL;
UPDATE public.roles SET display_name = 'Administrator'           WHERE lower(role_name) = 'supervisor'             AND display_name IS NULL;
UPDATE public.roles SET display_name = 'Komercijalista'          WHERE lower(role_name) = 'commercial'             AND display_name IS NULL;
UPDATE public.roles SET display_name = 'Programer'               WHERE lower(role_name) = 'developer'              AND display_name IS NULL;
UPDATE public.roles SET display_name = 'Koordinator proizvodnje' WHERE lower(role_name) = 'production_coordinator' AND display_name IS NULL;
UPDATE public.roles SET display_name = 'Knjigovođa'              WHERE lower(role_name) = 'accountant'             AND display_name IS NULL;

-- --- app_settings descriptions into Serbian ---------------------------------

UPDATE public.app_settings SET description = 'Cena jednog radnog sata.'          WHERE description = 'Hourly rate';
UPDATE public.app_settings SET description = 'Najveći priznati procenat učinka.' WHERE description = 'Max allowed efficiency';
UPDATE public.app_settings SET description = 'Iznos toplog obroka po danu.'      WHERE description = 'Meal per day';
UPDATE public.app_settings SET description = 'Podrazumevani mesečni bonus.'      WHERE description = 'Default bonus';
UPDATE public.app_settings SET description = 'Naknada za prevoz po radnom danu.' WHERE description = 'Transport per day';
