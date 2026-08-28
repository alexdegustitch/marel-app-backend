-- =============================================================================
-- Every role the application knows about exists in the database
-- =============================================================================
-- WHAT CHANGES
--   roles — inserts `production_coordinator` and `accountant` if they are absent.
--   Nothing else. No column, constraint, trigger or existing row is touched.
--
-- WHY AT ALL
--   Roles are not seeded by any migration: `admin`, `supervisor`, `commercial`
--   and `developer` were created by hand on the running database, and the two
--   newer ones the same way. That is why the baseline dump carries an empty
--   `roles` section — the table's contents were never part of the schema.
--
--   Authorization now names these roles in code (`RolePermissions`), so a role
--   that exists on one database and not another is a difference that decides who
--   may open which screen. A fresh database built from the migrations has to
--   arrive with the same six roles the live one has, or the first person given
--   `production_coordinator` there cannot be given it at all.
--
-- WHY ON CONFLICT DO NOTHING
--   The live database already holds both rows, created 2026-08-11 and
--   2026-08-28. This migration must be a no-op there and must not renumber,
--   rename or re-timestamp anything. `uq_roles_name_ci` is the unique index on
--   lower(role_name), which is what the conflict target names.
--
-- WHY NOT THE OTHER FOUR
--   They are inserted here too, for exactly the same reason and with exactly the
--   same no-op behaviour on the live database: a database built from migrations
--   alone should be usable, and four of the six being invisible to it was the
--   bug this fixes, not a rule to preserve.
--
-- MIGRATION IMPACT
--   · Additive and idempotent. Safe to run on the live database, where it does
--     nothing at all.
--   · `id` is GENERATED ALWAYS AS IDENTITY, so the new rows take whatever
--     numbers the sequence hands out. Nothing references a role by number
--     literal — `users.role_id` is a foreign key resolved by lookup.
--   · No rollback step is needed: deleting a role is refused by
--     `fk_users_role_id` while anybody holds it, which is the correct answer.
-- =============================================================================

INSERT INTO public.roles (role_name)
VALUES ('admin'),
       ('supervisor'),
       ('commercial'),
       ('developer'),
       ('production_coordinator'),
       ('accountant')
ON CONFLICT (lower((role_name)::text)) DO NOTHING;
