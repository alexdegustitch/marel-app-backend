# Archived migrations

These 110 scripts are history, not an active migration path. Their combined
effect — schema and reference data alike — is folded into
`src/main/resources/db/migration/V1__baseline.sql`, which is what Flyway
actually applies. Nothing here runs automatically.

They stay checked in and readable because each one explains a real decision
(see their headers — many document the business reasoning behind a schema
change, not just the SQL). Deleting them would delete that record.

New migrations do not go here. They go in `src/main/resources/db/migration/`
as `V<version>__<description>.sql`, following Flyway's own versioning.

## The one exception

`AbstractIntegrationTest.runMigrationScript(String)` re-runs
`2026-08-01-03-employee-payroll-value-backfill.sql` from this directory
against seeded rows, in `PayrollValueBackfillIT` — that migration's own
`DO $$` verification block only checks whatever it finds in the database it
runs against, which on a fresh Flyway-built schema is nothing, so the only
way to test the backfill logic itself is to give it known input and read
back what it produced. If this file moves or is renamed, update both the
path in `AbstractIntegrationTest` and the `SCRIPT` constant in that test.
